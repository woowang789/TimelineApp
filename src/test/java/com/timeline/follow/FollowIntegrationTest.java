package com.timeline.follow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 팔로우/언팔로우 통합 테스트 (작업 0.9 · 마스터 &sect;4.1 &sect;4.4).
 *
 * <p>이 클래스의 본론은 두 가지다.
 * <ol>
 *   <li><strong>카운터가 행 변경과 같은 트랜잭션에서 움직인다</strong> — 그래서 검증은 API 응답이 아니라
 *       {@code users.follower_count} 컬럼을 직접 읽어서 한다. 응답만 보면 "숫자를 계산해서 돌려준 것"과
 *       "DB에 반영된 것"을 구분할 수 없다.</li>
 *   <li><strong>self-follow 행은 API로 건드릴 수 없다</strong> — 자기 자신 대상 요청이 400이고,
 *       그 뒤에도 가입 때 들어간 행이 그대로 남아 있는지까지 본다. 400만 확인하면
 *       "거절은 했는데 이미 지운 뒤"인 경우를 놓친다.</li>
 * </ol>
 *
 * <p>{@code GET /api/v1/users/{userId}}도 0.9의 범위라 여기서 함께 검증한다 — 로드맵의 통합 테스트
 * 목록(&sect;1)에 사용자 조회 전용 클래스가 없고, 실제로 그 API가 보여 주는 값의 대부분이
 * 팔로우가 바꾼 {@code followerCount}다.
 */
class FollowIntegrationTest extends IntegrationTestSupport {

	/** 존재하지 않는 사용자 id. TRUNCATE로 채번이 1부터 다시 시작하므로 이 값에 닿을 일이 없다. */
	private static final long MISSING_USER_ID = 999_999L;

	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("팔로우하면 follows 행이 생기고 대상의 follower_count가 1 올라간다")
	void followInsertsRowAndIncreasesCounter() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);

		ResponseEntity<String> response = requestFollow(alice, bob.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(followRowCount(alice.userId(), bob.userId())).isEqualTo(1);
		// 컬럼을 직접 읽는다 — 원자 UPDATE가 같은 트랜잭션에서 커밋되었는지의 증거다.
		assertThat(followerCount(bob.userId())).isEqualTo(1);
		// 팔로우한 쪽의 팔로워 수는 그대로다. 방향을 반대로 구현하면 여기서 걸린다.
		assertThat(followerCount(alice.userId())).isZero();

		// 사용자 조회 API도 같은 값을 보여 준다.
		JsonNode user = objectMapper.readTree(requestGetUser(alice, bob.userId()).getBody());
		assertThat(user.get("id").asLong()).isEqualTo(bob.userId());
		assertThat(user.get("username").asText()).isEqualTo("bob");
		assertThat(user.get("nickname").asText()).isEqualTo("bob");
		assertThat(user.get("followerCount").asInt()).isEqualTo(1);
		// 승격 로직은 Phase 3이므로 지금은 언제나 false다.
		assertThat(user.get("influencer").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("같은 대상을 두 번 팔로우하면 409이고, 카운터는 1에서 더 오르지 않는다")
	void rejectsDuplicateFollow() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		requestFollow(alice, bob.userId());

		ResponseEntity<String> response = requestFollow(alice, bob.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(response)).isEqualTo("DUPLICATE_FOLLOW");
		assertThat(followRowCount(alice.userId(), bob.userId())).isEqualTo(1);
		// 거절된 요청이 카운터만 올려 놓고 끝나면 팔로워 수가 실제 관계 수와 어긋난다.
		assertThat(followerCount(bob.userId())).isEqualTo(1);
	}

	@Test
	@DisplayName("언팔로우하면 follows 행이 사라지고 follower_count가 1 내려간다")
	void unfollowDeletesRowAndDecreasesCounter() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		requestFollow(alice, bob.userId());

		ResponseEntity<String> response = requestUnfollow(alice, bob.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(followRowCount(alice.userId(), bob.userId())).isZero();
		assertThat(followerCount(bob.userId())).isZero();
	}

	@Test
	@DisplayName("자기 자신을 팔로우하면 400이고, 가입 때 들어간 self-follow 행은 그대로다")
	void rejectsSelfFollow() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = requestFollow(alice, alice.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("SELF_FOLLOW_FORBIDDEN");
		// 가입 시 1건 들어간 그대로 — 늘지도 줄지도 않아야 한다.
		assertThat(followRowCount(alice.userId(), alice.userId())).isEqualTo(1);
		assertThat(followerCount(alice.userId())).isZero();
	}

	@Test
	@DisplayName("자기 자신을 언팔로우하면 400이고, self-follow 행은 지워지지 않는다")
	void rejectsSelfUnfollow() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = requestUnfollow(alice, alice.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("SELF_FOLLOW_FORBIDDEN");
		// 이 행이 사라지면 그 사용자의 Pull 결과에서 자기 글이 영영 빠진다(§4.3).
		assertThat(followRowCount(alice.userId(), alice.userId())).isEqualTo(1);
	}

	@Test
	@DisplayName("없는 사용자를 팔로우하면 404다 — FK 위반(409)으로 흘러가지 않는다")
	void rejectsFollowOfMissingUser() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = requestFollow(alice, MISSING_USER_ID);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("USER_NOT_FOUND");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("팔로우한 적 없는 대상을 언팔로우하면 404이고, 카운터가 음수로 내려가지 않는다")
	void rejectsUnfollowOfNonFollowedUser() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);

		ResponseEntity<String> response = requestUnfollow(alice, bob.userId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("FOLLOW_NOT_FOUND");
		// 삭제 0건인데 카운터만 내리면 follower_count가 -1이 된다.
		assertThat(followerCount(bob.userId())).isZero();
	}

	@Test
	@DisplayName("없는 사용자를 조회하면 404다")
	void rejectsGetOfMissingUser() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = requestGetUser(alice, MISSING_USER_ID);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("USER_NOT_FOUND");
	}

	@Test
	@DisplayName("토큰 없이는 조회도 팔로우도 언팔로우도 401이다")
	void rejectsUnauthenticatedRequests() {
		Tokens bob = signupAndLogin("bob", PASSWORD);

		assertThat(restTemplate.getForEntity("/api/v1/users/{userId}", String.class, bob.userId())
				.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(restTemplate.exchange("/api/v1/users/{userId}/follow", HttpMethod.POST,
				null, String.class, bob.userId()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(restTemplate.exchange("/api/v1/users/{userId}/follow", HttpMethod.DELETE,
				null, String.class, bob.userId()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 인증 이전에 막혔으므로 아무 행도 건드려지지 않았다 — bob의 self-follow 1건이 전부다.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isEqualTo(1);
	}

	private ResponseEntity<String> requestFollow(Tokens actor, Long targetUserId) {
		return restTemplate.exchange("/api/v1/users/{userId}/follow", HttpMethod.POST,
				bearer(actor.accessToken()), String.class, targetUserId);
	}

	private ResponseEntity<String> requestUnfollow(Tokens actor, Long targetUserId) {
		return restTemplate.exchange("/api/v1/users/{userId}/follow", HttpMethod.DELETE,
				bearer(actor.accessToken()), String.class, targetUserId);
	}

	private ResponseEntity<String> requestGetUser(Tokens actor, Long targetUserId) {
		return restTemplate.exchange("/api/v1/users/{userId}", HttpMethod.GET,
				bearer(actor.accessToken()), String.class, targetUserId);
	}

	private String errorCode(ResponseEntity<String> response) throws Exception {
		return objectMapper.readTree(response.getBody()).get("code").asText();
	}

	private int followerCount(Long userId) {
		return jdbcTemplate.queryForObject("SELECT follower_count FROM users WHERE id = ?", Integer.class, userId);
	}

	private int followRowCount(Long followerId, Long followeeId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
				Integer.class, followerId, followeeId);
	}
}
