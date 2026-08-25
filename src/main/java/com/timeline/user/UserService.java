package com.timeline.user;

import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
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
}
