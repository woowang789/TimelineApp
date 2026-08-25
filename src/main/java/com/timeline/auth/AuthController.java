package com.timeline.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API (마스터 &sect;6) — signup / login / reissue 3개가 전부다.
 *
 * <p>이 경로 전체가 {@code permitAll}이다(0.8). 토큰을 받으러 오는 요청에 토큰을 요구할 수는 없다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/** Access가 만료됐을 때 Refresh로 Access만 다시 받는다. Refresh는 갱신되지 않는다. */
	@PostMapping("/reissue")
	public ReissueResponse reissue(@Valid @RequestBody ReissueRequest request) {
		return authService.reissue(request);
	}
}
