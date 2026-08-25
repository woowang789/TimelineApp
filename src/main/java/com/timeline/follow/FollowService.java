package com.timeline.follow;

import com.timeline.common.api.CursorPageResponse;
import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
import com.timeline.user.UserService;
import com.timeline.user.UserSummary;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팔로우 도메인 서비스. 타 도메인이 follow에 접근하는 유일한 통로다(경계 규칙 1).
 *
 * <p>반대로 이 서비스가 user 도메인을 볼 때도 {@link UserService}만 본다 — 팔로워 수 증감도,
 * 목록의 이름 채우기도 전부 그 통로를 지난다.
 */
@Service
public class FollowService {

	/**
	 * 목록 조회 상한.
	 *
	 * <p>상한을 넘긴 요청은 400으로 거절하지 않고 이 값으로 깎는다. 상한은 클라이언트와의 계약이 아니라
	 * <strong>서버가 한 번에 만들 응답 크기의 방어선</strong>이기 때문이다 — 100건이면 users 조회의
	 * IN 절 크기도 100이 상한이 된다. 거절로 바꾸면 얻는 것 없이 클라이언트에 에러 처리만 하나 늘어난다.
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private final FollowRepository followRepository;
	private final UserService userService;

	public FollowService(FollowRepository followRepository, UserService userService) {
		this.followRepository = followRepository;
		this.userService = userService;
	}

	/**
	 * 가입 시 self-follow 행({@code follower_id = followee_id})을 1건 삽입한다 (마스터 &sect;4.3).
	 *
	 * <p><strong>왜 자기 자신을 팔로우시키는가.</strong> Pull 조회는
	 * {@code JOIN follows ON p.author_id = f.followee_id WHERE f.follower_id = :userId}라서
	 * 내가 쓴 글이 결과에 들어오지 않는다. 반면 Push는 본인 타임라인에 직접 ZADD한다.
	 * 그대로 두면 Phase 1과 Phase 2가 <em>서로 다른 데이터</em>를 반환하는데 p99를 비교하게 된다.
	 * 이 행 1건이 두 경로의 결과 집합을 같게 만들어 Phase 간 비교를 성립시킨다.
	 * (버린 대안: Pull 쿼리에 UNION ALL — 임시 테이블이 생겨 실행 계획 분석을 오염시킨다.)
	 *
	 * <p><strong>{@code follower_count}는 올리지 않는다.</strong> self-follow는 카운트에서 제외하기로 했으므로
	 * 가입 직후 팔로워 수는 0이다(&sect;4.3의 "대가"). 카운터 증감은 팔로우 API(0.9)의 몫이다.
	 *
	 * <p>트랜잭션 경계는 호출자에 있다 — users 행만 남고 self-follow 행이 빠지면
	 * 그 사용자의 Pull 결과에서 자기 글이 영구히 사라지므로 함께 커밋되어야 한다.
	 */
	public void insertSelfFollowRow(Long userId) {
		followRepository.save(Follow.create(userId, userId));
	}

	/**
	 * 팔로우 (작업 0.9).
	 *
	 * <p><strong>이 메서드가 트랜잭션 경계다.</strong> follows 행 삽입과 {@code follower_count} 증가는
	 * 나뉘어 커밋될 수 없다(마스터 &sect;4.4) — 한쪽만 남으면 "팔로우는 했는데 숫자는 그대로",
	 * 혹은 그 반대인 상태가 되고, 카운터의 SoT를 DB로 정한 결정이 그 순간 무의미해진다.
	 *
	 * <p>중복 팔로우는 <strong>두 겹</strong>으로 막는다. 사전 조회는 정상 경로에서 예외 대신 409를 만들고,
	 * UNIQUE 제약({@code uk_follows_follower_followee})은 그 조회와 INSERT 사이로 들어온 동시 요청을 막는다.
	 * 뒤엣것이 진짜 방어선이고 앞엣것은 사용성이다 — 0.6의 username 중복 처리와 같은 구조다.
	 *
	 * <p><strong>{@code is_influencer} 승격은 여기서 하지 않는다.</strong> 팔로워 수가 임계치(5,000)를
	 * 넘는 순간 Push 대상에서 빼는 판단은 Hybrid의 일이고, Phase 3에서 들어온다(마스터 &sect;7.2).
	 * 지금 넣으면 Phase 2a의 Push 측정이 "인플루언서 제외가 적용된 Push"가 되어 M2/M4의 비교가 흐려진다.
	 */
	@Transactional
	public void follow(Long followerId, Long followeeId) {
		// self-follow 행은 시스템 불변식이다 — API로 만들 수 없다(부록 B).
		if (followerId.equals(followeeId)) {
			throw new BusinessException(ErrorCode.SELF_FOLLOW_FORBIDDEN);
		}
		// FK에 맡기면 없는 사용자도 제약 위반(409)으로 수렴한다. 없는 대상은 404여야 한다.
		if (!userService.existsById(followeeId)) {
			throw new BusinessException(ErrorCode.USER_NOT_FOUND);
		}
		if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_FOLLOW);
		}

		try {
			// Follow는 IDENTITY 채번이라 save() 시점에 INSERT가 즉시 나간다 —
			// 그래서 UNIQUE 위반도 커밋까지 미뤄지지 않고 여기서 잡힌다.
			followRepository.save(Follow.create(followerId, followeeId));
		} catch (DataIntegrityViolationException e) {
			// 위 사전 조회와 INSERT 사이로 같은 팔로우가 들어온 경우. 사용자에게는 같은 상황이므로
			// 같은 409로 수렴시킨다. 이 트랜잭션은 통째로 롤백되므로 카운터는 손대지 않은 상태로 남는다.
			throw new BusinessException(ErrorCode.DUPLICATE_FOLLOW, e);
		}
		userService.increaseFollowerCount(followeeId);
	}

	/**
	 * 언팔로우 (작업 0.9). 행 삭제와 {@code follower_count} 감소가 같은 트랜잭션이다.
	 *
	 * <p>대상이 아예 없는 사용자인 경우도 삭제 0건이라 {@code FOLLOW_NOT_FOUND}로 수렴한다.
	 * 둘 다 404이고, 클라이언트가 할 일도 같다 — 구분하려고 존재 확인 조회를 하나 더 붙일 이유가 없다.
	 */
	@Transactional
	public void unfollow(Long followerId, Long followeeId) {
		// 언팔로우로도 self-follow 행을 지울 수 없다. 이 행이 사라진 사용자는
		// Pull 결과에서 자기 글이 영영 빠진다(§4.3).
		if (followerId.equals(followeeId)) {
			throw new BusinessException(ErrorCode.SELF_FOLLOW_FORBIDDEN);
		}
		if (followRepository.deleteFollow(followerId, followeeId) == 0) {
			throw new BusinessException(ErrorCode.FOLLOW_NOT_FOUND);
		}
		userService.decreaseFollowerCount(followeeId);
	}

	/** 팔로워 목록 — {@code userId}를 팔로우하는 사람들 (작업 0.10). */
	public CursorPageResponse<UserSummary> getFollowers(Long userId, Long cursor, int size) {
		int pageSize = normalizeSize(size);
		List<Follow> rows = followRepository.findFollowers(userId, startCursor(cursor), Limit.of(pageSize + 1));
		return toPage(rows, pageSize, Follow::getFollowerId);
	}

	/** 팔로잉 목록 — {@code userId}가 팔로우하는 사람들 (작업 0.10). */
	public CursorPageResponse<UserSummary> getFollowings(Long userId, Long cursor, int size) {
		int pageSize = normalizeSize(size);
		List<Follow> rows = followRepository.findFollowings(userId, startCursor(cursor), Limit.of(pageSize + 1));
		return toPage(rows, pageSize, Follow::getFolloweeId);
	}

	private static int normalizeSize(int size) {
		return Math.clamp(size, 1, MAX_PAGE_SIZE);
	}

	/** 커서가 없으면(첫 페이지) 상한이 없다는 뜻이다. 쿼리 술어를 {@code id < ?} 하나로 유지한다. */
	private static Long startCursor(Long cursor) {
		return cursor == null ? Long.MAX_VALUE : cursor;
	}

	/**
	 * 조회한 팔로우 행들을 페이지 응답으로 만든다.
	 *
	 * <p><strong>{@code hasNext}는 size+1개를 읽어서 판정한다.</strong> 별도 COUNT 쿼리를 쓰면
	 * 페이지마다 전체 스캔이 하나 붙고, 그 결과도 첫 쿼리와 같은 시점의 것이라는 보장이 없다.
	 * 한 건 더 읽고 버리는 쪽이 싸고 정확하다.
	 *
	 * @param counterpart 팔로우 행에서 <em>상대방</em>의 id를 꺼내는 방법.
	 *                    팔로워 목록이면 {@code followerId}, 팔로잉 목록이면 {@code followeeId}다
	 */
	private CursorPageResponse<UserSummary> toPage(List<Follow> rows, int pageSize,
			Function<Follow, Long> counterpart) {
		boolean hasNext = rows.size() > pageSize;
		List<Follow> page = hasNext ? rows.subList(0, pageSize) : rows;
		if (page.isEmpty()) {
			return new CursorPageResponse<>(List.of(), null, false);
		}

		List<Long> userIds = page.stream().map(counterpart).toList();
		// users 조회는 UserService를 지난다 — follows JOIN users는 경계 규칙 1 위반이다.
		Map<Long, UserSummary> summaries = userService.findSummaries(userIds).stream()
				.collect(Collectors.toMap(UserSummary::id, Function.identity()));
		// IN 절 조회는 순서를 보장하지 않으므로, 목록의 정렬 기준인 follows.id DESC 순서로 다시 세운다.
		List<UserSummary> data = userIds.stream().map(summaries::get).toList();

		// 다음 페이지가 없으면 커서도 없다(CursorPageResponse 규약).
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		return new CursorPageResponse<>(data, nextCursor, hasNext);
	}
}
