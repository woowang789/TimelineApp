package com.timeline.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 로그인 통합 테스트 (작업 0.7 · 마스터 &sect;5, &sect;6).
 *
 * <p>검증 대상은 세 가지다.
 * <ol>
 *   <li>토큰 2종이 발급된다</li>
 *   <li>Refresh Token이 <strong>Redis {@code refresh:{userId}}에 TTL 14일로</strong> 남는다 — 실측한다</li>
 *   <li>실패는 사유를 구분하지 않는다 — 없는 사용자와 틀린 비밀번호의 응답이 <strong>바이트 단위로 같다</strong></li>
 * </ol>
 */
class LoginIntegrationTest extends IntegrationTestSupport {

	private static final String USERNAME = "alice";
	private static final String PASSWORD = "password123";
	private static final Duration REFRESH_TTL = Duration.ofDays(14);

	@Autowired
	private ObjectMapper objectMapper;

	private Long userId;

	@BeforeEach
	void signup() {
		userId = restTemplate.postForEntity("/api/v1/auth/signup",
				Map.of("username", USERNAME, "password", PASSWORD, "nickname", "앨리스"),
				SignupResponse.class).getBody().id();
	}

	@Test
	@DisplayName("로그인하면 Access/Refresh를 받고, Refresh는 refresh:{userId}에 TTL 14일로 저장된다")
	void issuesTokensAndStoresRefreshTokenWithTtl() {
		ResponseEntity<LoginResponse> response = login(USERNAME, PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		LoginResponse body = response.getBody();
		assertThat(body.accessToken()).isNotBlank();
		assertThat(body.refreshToken()).isNotBlank();
		// 두 토큰은 서로 달라야 한다 — 같다면 type 클레임 구분이 동작하지 않는다는 뜻이다.
		assertThat(body.accessToken()).isNotEqualTo(body.refreshToken());

		String key = "refresh:" + userId;
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(body.refreshToken());
		// TTL은 발급 시각과 조회 시각 사이의 왕복만큼 이미 줄어 있다. 상한은 14일, 하한은 넉넉히 1분 전.
		Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttlSeconds).isBetween(REFRESH_TTL.toSeconds() - 60, REFRESH_TTL.toSeconds());
	}

	@Test
	@DisplayName("비밀번호가 틀리면 401이고, Refresh Token은 저장되지 않는다")
	void rejectsWrongPassword() throws Exception {
		ResponseEntity<String> response = loginRaw(USERNAME, "wrongpassword");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.get("code").asText()).isEqualTo("LOGIN_FAILED");
		assertThat(redisTemplate.hasKey("refresh:" + userId)).isFalse();
	}

	@Test
	@DisplayName("없는 사용자와 틀린 비밀번호의 응답은 구분되지 않는다")
	void doesNotDistinguishUnknownUserFromWrongPassword() {
		ResponseEntity<String> wrongPassword = loginRaw(USERNAME, "wrongpassword");
		ResponseEntity<String> unknownUser = loginRaw("nobody", PASSWORD);

		assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 상태 코드뿐 아니라 본문까지 같아야 한다. 메시지가 한 글자라도 다르면
		// 로그인 API가 "이 username이 존재하는가"를 알려주는 도구가 된다.
		assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
	}

	@Test
	@DisplayName("재로그인하면 Refresh Token이 덮어써진다 — 사용자당 1개")
	void overwritesRefreshTokenOnRelogin() {
		String firstRefreshToken = login(USERNAME, PASSWORD).getBody().refreshToken();

		String secondRefreshToken = login(USERNAME, PASSWORD).getBody().refreshToken();

		assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);
		// 키가 userId 단위라 두 번째 SET이 첫 번째를 덮어쓴다 — 토큰이 쌓이지 않는다.
		assertThat(redisTemplate.keys("refresh:*")).containsExactly("refresh:" + userId);
		assertThat(redisTemplate.opsForValue().get("refresh:" + userId)).isEqualTo(secondRefreshToken);
	}

	private ResponseEntity<LoginResponse> login(String username, String password) {
		return restTemplate.postForEntity("/api/v1/auth/login",
				Map.of("username", username, "password", password), LoginResponse.class);
	}

	/** 에러 본문을 그대로 비교해야 하므로 역직렬화하지 않고 String으로 받는다. */
	private ResponseEntity<String> loginRaw(String username, String password) {
		return restTemplate.postForEntity("/api/v1/auth/login",
				Map.of("username", username, "password", password), String.class);
	}
}
