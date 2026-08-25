package com.timeline.auth;

import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
import com.timeline.follow.FollowService;
import com.timeline.user.UserCredentials;
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
	private final JwtProvider jwtProvider;
	private final RefreshTokenRepository refreshTokenRepository;

	public AuthService(UserService userService, FollowService followService, PasswordEncoder passwordEncoder,
			JwtProvider jwtProvider, RefreshTokenRepository refreshTokenRepository) {
		this.userService = userService;
		this.followService = followService;
		this.passwordEncoder = passwordEncoder;
		this.jwtProvider = jwtProvider;
		this.refreshTokenRepository = refreshTokenRepository;
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

	/**
	 * 로그인 — 자격 검증 후 Access/Refresh를 발급하고 Refresh를 Redis에 남긴다.
	 *
	 * <p><strong>{@code @Transactional}을 걸지 않는다.</strong> DB 접근은 사용자 단건 조회 하나뿐인데,
	 * 트랜잭션으로 감싸면 그 뒤의 BCrypt 대조(수십 ms)와 Redis 왕복이 커넥션을 쥔 채로 일어난다.
	 * 로그인은 쓰기 경로가 DB에 없으므로 원자성을 보장할 것도 없다.
	 */
	public LoginResponse login(LoginRequest request) {
		UserCredentials credentials = userService.findCredentialsByUsername(request.username())
				.orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
		if (!passwordEncoder.matches(request.password(), credentials.encodedPassword())) {
			// 위의 "사용자 없음"과 같은 예외다 — 두 경우의 응답이 구분되면 안 된다.
			throw new BusinessException(ErrorCode.LOGIN_FAILED);
		}

		Long userId = credentials.id();
		String refreshToken = jwtProvider.createRefreshToken(userId);
		// 키가 refresh:{userId}라 이 SET이 이전 로그인의 토큰을 덮어쓴다 → 사용자당 1개(§5).
		refreshTokenRepository.save(userId, refreshToken);

		return new LoginResponse(jwtProvider.createAccessToken(userId), refreshToken);
	}

	/**
	 * Access Token 재발급.
	 *
	 * <p>검증은 세 겹이다 — 서명·만료(JWT 자체) + 종류가 REFRESH인지 + <strong>Redis 저장값과 같은지</strong>.
	 * 앞의 둘만 보면 서버는 자기가 발급한 모든 Refresh Token을 14일 내내 받아들이게 된다.
	 * 저장값 대조가 있어야 재로그인으로 이전 토큰을 무효화할 수 있다.
	 *
	 * <p>Refresh Token 자체는 갱신하지 않는다 — 로드맵 0.7은 "Access 재발급"까지다.
	 */
	public ReissueResponse reissue(ReissueRequest request) {
		Long userId = jwtProvider.parseUserId(request.refreshToken(), TokenType.REFRESH)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

		String storedToken = refreshTokenRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		if (!storedToken.equals(request.refreshToken())) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}

		return new ReissueResponse(jwtProvider.createAccessToken(userId));
	}
}
