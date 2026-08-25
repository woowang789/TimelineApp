package com.timeline.follow;

import org.springframework.stereotype.Service;

/**
 * 팔로우 도메인 서비스. 타 도메인이 follow에 접근하는 유일한 통로다(경계 규칙 1).
 */
@Service
public class FollowService {

	private final FollowRepository followRepository;

	public FollowService(FollowRepository followRepository) {
		this.followRepository = followRepository;
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
}
