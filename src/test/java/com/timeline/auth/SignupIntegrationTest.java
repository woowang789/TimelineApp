package com.timeline.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회원가입 통합 테스트 (작업 0.6 · 마스터 &sect;4.3).
 *
 * <p>이 테스트의 본론은 "가입이 된다"가 아니라 <strong>self-follow 행이 같은 트랜잭션에서 1건 생기고
 * {@code follower_count}는 0으로 남는다</strong>는 것이다. 앞의 것은 Phase 1(Pull)과 Phase 2a(Push)의
 * 결과 집합을 같게 만드는 장치고, 뒤의 것은 그 장치가 사용자에게 보이는 숫자를 오염시키지 않는다는 확인이다.
 */
class SignupIntegrationTest extends IntegrationTestSupport {

	private static final String RAW_PASSWORD = "password123";

	/** BCrypt 해시 형식 — {@code $2a$10$} + salt·hash 53자, 총 60자. */
	private static final String BCRYPT_PATTERN = "^\\$2[ab]\\$\\d{2}\\$.{53}$";

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	@DisplayName("가입하면 users 행과 self-follow 행이 함께 생기고, follower_count는 0으로 남는다")
	void createsUserWithSelfFollowRow() throws Exception {
		ResponseEntity<String> response = requestSignup("alice", RAW_PASSWORD, "앨리스");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		JsonNode body = objectMapper.readTree(response.getBody());
		long userId = body.get("id").asLong();
		assertThat(body.get("username").asText()).isEqualTo("alice");
		assertThat(body.get("nickname").asText()).isEqualTo("앨리스");
		// 응답 본문 어디에도 비밀번호가 없어야 한다 — 필드명도, 해시 값도.
		assertThat(response.getBody()).doesNotContain("password").doesNotContain("$2");

		Map<String, Object> user = jdbcTemplate.queryForMap(
				"SELECT id, username, nickname, password, follower_count FROM users");
		assertThat(user.get("id")).isEqualTo(userId);
		assertThat(user.get("username")).isEqualTo("alice");
		assertThat(user.get("nickname")).isEqualTo("앨리스");
		// 평문이 아니라 BCrypt 해시가, 그것도 방금 보낸 비밀번호의 해시가 저장되어야 한다.
		String storedPassword = (String) user.get("password");
		assertThat(storedPassword).matches(BCRYPT_PATTERN);
		assertThat(passwordEncoder.matches(RAW_PASSWORD, storedPassword)).isTrue();
		// self-follow 행이 1건 있어도 팔로워 수는 0이다 (§4.3의 "대가").
		assertThat(user.get("follower_count")).isEqualTo(0);

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isEqualTo(1);
		Map<String, Object> follow = jdbcTemplate.queryForMap("SELECT follower_id, followee_id FROM follows");
		assertThat(follow.get("follower_id")).isEqualTo(userId);
		assertThat(follow.get("followee_id")).isEqualTo(userId);
	}

	@Test
	@DisplayName("이미 있는 username으로 가입하면 409이고, 아무 행도 남기지 않는다")
	void rejectsDuplicateUsername() throws Exception {
		requestSignup("alice", RAW_PASSWORD, "앨리스");

		ResponseEntity<String> response = requestSignup("alice", "otherpassword", "다른앨리스");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("DUPLICATE_USERNAME");
		// 실패한 가입이 트랜잭션째 롤백되었는지 — users도 follows도 첫 가입분 1건씩만 남아야 한다.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("username이 비어 있으면 400이고, 가입 트랜잭션 자체가 시작되지 않는다")
	void rejectsBlankUsername() {
		ResponseEntity<String> response = requestSignup("", RAW_PASSWORD, "앨리스");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isZero();
	}

	private ResponseEntity<String> requestSignup(String username, String password, String nickname) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, String> body = Map.of("username", username, "password", password, "nickname", nickname);
		// 응답을 String으로 받는다 — 역직렬화가 필드를 걸러 주면 "비밀번호가 응답에 없다"를 검증할 수 없다.
		return restTemplate.postForEntity("/api/v1/auth/signup", new HttpEntity<>(body, headers), String.class);
	}
}
