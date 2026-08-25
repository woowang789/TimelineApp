package com.timeline.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import com.timeline.support.ProtectedTestEndpoint;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 재발급 통합 테스트 (작업 0.7 · 마스터 &sect;6).
 *
 * <p>재발급의 검증은 세 겹이고(서명·만료 / 종류 / Redis 저장값), 이 클래스는 그 세 겹이
 * <strong>각각 독립적으로 필요한지</strong>를 확인한다 — 겹 하나를 빼면 통과해 버리는 케이스가 하나씩 있다.
 */
class ReissueIntegrationTest extends IntegrationTestSupport {

	private static final String USERNAME = "alice";
	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtProvider jwtProvider;

	private Tokens tokens;

	@BeforeEach
	void login() {
		tokens = signupAndLogin(USERNAME, PASSWORD);
	}

	@Test
	@DisplayName("정상 Refresh로 Access를 재발급받고, 그 토큰으로 보호 경로에 접근할 수 있다")
	void reissuesUsableAccessToken() throws Exception {
		ResponseEntity<ReissueResponse> response = reissue(tokens.refreshToken());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String reissuedAccessToken = response.getBody().accessToken();
		assertThat(reissuedAccessToken).isNotBlank();

		// "발급됐다"로는 부족하다 — 실제로 인증을 통과하는 토큰인지까지 본다.
		ResponseEntity<String> protectedResponse = restTemplate.exchange(ProtectedTestEndpoint.PATH,
				HttpMethod.GET, bearer(reissuedAccessToken), String.class);
		assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(objectMapper.readTree(protectedResponse.getBody()).get("userId").asLong())
				.isEqualTo(tokens.userId());
	}

	@Test
	@DisplayName("서명이 훼손된 Refresh는 거부된다")
	void rejectsTamperedSignature() throws Exception {
		// 페이로드는 그대로 두고 서명 마지막 한 글자만 바꾼다 — 위조 시도의 최소 형태다.
		String token = tokens.refreshToken();
		char last = token.charAt(token.length() - 1);
		String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

		assertRejected(reissueRaw(tampered));
	}

	@Test
	@DisplayName("만료된 Refresh는 거부된다")
	void rejectsExpiredToken() throws Exception {
		// TTL을 음수로 줘서 "이미 만료된" 토큰을 만든다. 서명은 정상이므로 만료 검사만이 이것을 막는다.
		// 이 발급 경로(JwtProvider#create)가 package-private이라 같은 패키지의 테스트에서만 보인다.
		String expired = jwtProvider.create(tokens.userId(), TokenType.REFRESH, Duration.ofSeconds(-1));

		assertRejected(reissueRaw(expired));
	}

	@Test
	@DisplayName("재로그인 이후의 이전 Refresh는 거부된다 — Redis 저장값과 다르기 때문")
	void rejectsSupersededToken() throws Exception {
		String previousRefreshToken = tokens.refreshToken();
		// 재로그인이 refresh:{userId}를 덮어쓴다. 이전 토큰은 서명도 만료도 멀쩡하다 —
		// 저장값 대조가 없으면 14일 내내 유효한 토큰으로 남는다.
		loginAgain();

		assertRejected(reissueRaw(previousRefreshToken));
	}

	@Test
	@DisplayName("Access Token으로는 재발급받을 수 없다 — 종류 클레임")
	void rejectsAccessTokenAsRefresh() throws Exception {
		assertRejected(reissueRaw(tokens.accessToken()));
	}

	@Test
	@DisplayName("Refresh Token으로는 보호 API에 접근할 수 없다 — 종류 클레임")
	void rejectsRefreshTokenOnProtectedApi() {
		ResponseEntity<String> response = restTemplate.exchange(ProtectedTestEndpoint.PATH,
				HttpMethod.GET, bearer(tokens.refreshToken()), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/** 같은 계정으로 다시 로그인해 Refresh Token을 갈아 끼운다. */
	private void loginAgain() {
		restTemplate.postForEntity("/api/v1/auth/login",
				Map.of("username", USERNAME, "password", PASSWORD), LoginResponse.class);
	}

	private void assertRejected(ResponseEntity<String> response) throws Exception {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		JsonNode body = objectMapper.readTree(response.getBody());
		// 사유(위조·만료·불일치)를 구분하지 않는다 — 전부 같은 코드다.
		assertThat(body.get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
	}

	private ResponseEntity<ReissueResponse> reissue(String refreshToken) {
		return restTemplate.postForEntity("/api/v1/auth/reissue",
				Map.of("refreshToken", refreshToken), ReissueResponse.class);
	}

	private ResponseEntity<String> reissueRaw(String refreshToken) {
		return restTemplate.postForEntity("/api/v1/auth/reissue",
				Map.of("refreshToken", refreshToken), String.class);
	}
}
