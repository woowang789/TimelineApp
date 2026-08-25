package com.timeline.user;

import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 사용자 도메인 서비스. 타 도메인이 user에 접근하는 유일한 통로다(경계 규칙 1).
 */
@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * 신규 사용자를 저장하고 채번된 id를 돌려준다.
	 *
	 * <p><strong>User 엔티티가 아니라 id를 반환한다.</strong> 엔티티를 그대로 넘기면
	 * 호출자(auth)가 user 도메인의 엔티티를 직접 다루게 되어 경계 규칙 1이 깨진다.
	 *
	 * <p>트랜잭션 경계는 호출자에 있다 — 가입은 이 저장과 self-follow 삽입이 함께 성공해야 하므로
	 * {@code AuthService#signup}이 열어 둔 트랜잭션에 참여한다.
	 *
	 * @param encodedPassword 이미 해시된 비밀번호. 평문이 이 경로로 들어오면 안 된다.
	 */
	public Long create(String username, String encodedPassword, String nickname) {
		if (userRepository.existsByUsername(username)) {
			throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
		}
		try {
			// User는 IDENTITY 채번이라 save() 시점에 INSERT가 즉시 나간다 —
			// 그래서 UNIQUE 위반도 커밋까지 미뤄지지 않고 여기서 잡힌다.
			return userRepository.save(User.create(username, encodedPassword, nickname)).getId();
		} catch (DataIntegrityViolationException e) {
			// 위 존재 검사와 INSERT 사이의 틈으로 동시 가입이 들어오면 UNIQUE 제약이 막는다.
			// 그 경우도 사용자 입장에서는 같은 상황이므로 같은 409로 수렴시킨다.
			throw new BusinessException(ErrorCode.DUPLICATE_USERNAME, e);
		}
	}

	/**
	 * 로그인 검증에 쓸 자격 정보를 찾는다. 없으면 빈 값 —
	 * "없는 사용자"와 "비밀번호 불일치"를 같은 응답으로 합치는 것은 호출자(auth)의 몫이다.
	 */
	public Optional<UserCredentials> findCredentialsByUsername(String username) {
		return userRepository.findByUsername(username)
				.map(user -> new UserCredentials(user.getId(), user.getPassword()));
	}

	/** 사용자 단건 조회. 없으면 404다. */
	public UserResponse getUser(Long userId) {
		return userRepository.findById(userId)
				.map(UserResponse::from)
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	/**
	 * 존재 여부만 확인한다 — 팔로우 대상이 실재하는지 보는 용도다(follow 도메인이 호출).
	 *
	 * <p>{@link #getUser}로 대신하지 않는 이유: 팔로우 API는 대상의 이름도 팔로워 수도 쓰지 않는다.
	 * 존재 확인에 행을 통째로 읽어 오면 쓰기 경로에 불필요한 조회가 하나 붙는다.
	 */
	public boolean existsById(Long userId) {
		return userRepository.existsById(userId);
	}

	/**
	 * 팔로워 수를 원자적으로 +1 한다. 트랜잭션 경계는 호출자(follow)에 있다 —
	 * follows 행 삽입과 함께 커밋되어야 한다(마스터 &sect;4.4).
	 */
	public void increaseFollowerCount(Long userId) {
		userRepository.increaseFollowerCount(userId);
	}

	/** 팔로워 수를 원자적으로 −1 한다. 트랜잭션 경계는 호출자(follow)에 있다. */
	public void decreaseFollowerCount(Long userId) {
		userRepository.decreaseFollowerCount(userId);
	}

	/**
	 * id 목록으로 사용자 요약을 찾는다 — 팔로워/팔로잉 목록(0.10)이 이름을 채우는 통로다.
	 *
	 * <p><strong>반환 순서는 보장하지 않는다.</strong> IN 절 조회의 순서는 DB가 정하는 것이고,
	 * 목록의 정렬 기준(follows.id DESC)을 아는 쪽은 호출자다. 여기서 순서까지 맞춰 주면
	 * user 도메인이 follow의 페이지네이션 규칙을 알게 된다.
	 */
	public List<UserSummary> findSummaries(Collection<Long> userIds) {
		return userRepository.findAllById(userIds).stream()
				.map(user -> new UserSummary(user.getId(), user.getUsername(), user.getNickname()))
				.toList();
	}
}
