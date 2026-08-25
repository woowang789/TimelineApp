package com.timeline.auth;

import com.timeline.follow.FollowService;
import com.timeline.user.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 서비스.
 *
 * <p>user·follow 도메인을 <strong>Service 계층으로만</strong> 참조한다(경계 규칙 1).
 * Repository나 엔티티는 이 패키지에서 보이지 않는다.
 */
@Service
public class AuthService {

	private final UserService userService;
	private final FollowService followService;
	private final PasswordEncoder passwordEncoder;

	public AuthService(UserService userService, FollowService followService, PasswordEncoder passwordEncoder) {
		this.userService = userService;
		this.followService = followService;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 회원가입.
	 *
	 * <p><strong>이 메서드가 트랜잭션 경계다.</strong> users 행 삽입과 self-follow 행 삽입은
	 * 나뉘어 커밋될 수 없다 — self-follow 행이 없는 사용자는 Pull 조회에서 자기 글을 영영 못 보고,
	 * 그러면 Phase 1과 Phase 2의 결과 집합이 달라져 비교 자체가 무의미해진다(&sect;4.3).
	 * 그래서 두 호출을 하나의 트랜잭션에 묶고, 각 도메인 서비스는 경계를 열지 않고 여기에 참여한다.
	 */
	@Transactional
	public SignupResponse signup(SignupRequest request) {
		Long userId = userService.create(
				request.username(),
				passwordEncoder.encode(request.password()),
				request.nickname());

		followService.insertSelfFollowRow(userId);

		return new SignupResponse(userId, request.username(), request.nickname());
	}
}
