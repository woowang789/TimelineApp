package com.timeline.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 좋아요 통합 테스트 (작업 0.11 · 마스터 &sect;4.4).
 *
 * <p>본론은 <strong>likes 행과 {@code like_count}가 하나의 트랜잭션</strong>이라는 것이다.
 * 성공 경로에서 둘이 함께 움직이는 것만 봐서는 증명이 되지 않는다 — 둘을 따로 커밋하는 구현도
 * 성공 경로는 똑같이 통과하기 때문이다. 그래서 <strong>실패 경로</strong>를 함께 본다:
 * 중복 좋아요(409)와 누른 적 없는 취소(404)에서 카운터가 <em>움직인 흔적조차 없어야</em> 한다.
 * 카운터를 먼저 올리고 행 삽입이 터졌을 때 롤백이 되지 않으면 여기서 잡힌다.
 *
 * <p>Redis는 이 테스트에 등장하지 않는다. 좋아요의 읽기 경로({@code post:{postId}} 캐시)는
 * Phase 2a의 몫이고 Phase 0은 DB 쓰기 절반만 만든다(로드맵 4.7절).
 */
class LikeIntegrationTest extends IntegrationTestSupport {

	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("좋아요하면 likes 행이 생기고 like_count가 함께 1이 된다")
	void likeInsertsRowAndIncrementsCounter() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");

		ResponseEntity<String> response = like(bob.accessToken(), postId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		Map<String, Object> likeRow = jdbcTemplate.queryForMap("SELECT post_id, user_id FROM likes");
		assertThat(likeRow.get("post_id")).isEqualTo(postId);
		assertThat(likeRow.get("user_id")).isEqualTo(bob.userId());
		assertThat(likeCountOf(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 사용자가 두 번 좋아요하면 409이고, 카운터는 1에서 움직이지 않는다")
	void rejectsDuplicateLikeWithoutTouchingCounter() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");
		like(bob.accessToken(), postId);

		ResponseEntity<String> response = like(bob.accessToken(), postId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("DUPLICATE_LIKE");
		// UNIQUE 위반으로 행 삽입이 막혔다면 앞서 올린 카운터도 함께 롤백되어야 한다.
		assertThat(likeCountOf(postId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 사용자의 좋아요는 막히지 않는다 — 카운터가 2가 된다")
	void allowsLikeFromDifferentUsers() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		Tokens carol = signupAndLogin("carol", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");

		like(bob.accessToken(), postId);
		like(carol.accessToken(), postId);

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isEqualTo(2);
		assertThat(likeCountOf(postId)).isEqualTo(2);
	}

	@Test
	@DisplayName("취소하면 likes 행이 사라지고 like_count도 함께 줄어든다")
	void unlikeDeletesRowAndDecrementsCounter() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");
		like(bob.accessToken(), postId);

		ResponseEntity<String> response = unlike(bob.accessToken(), postId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isZero();
		assertThat(likeCountOf(postId)).isZero();
	}

	@Test
	@DisplayName("좋아요하지 않은 글을 취소하면 404이고, 카운터는 그대로다")
	void rejectsUnlikeWithoutLike() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		Tokens carol = signupAndLogin("carol", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");
		like(bob.accessToken(), postId);

		// carol은 누른 적이 없다.
		ResponseEntity<String> response = unlike(carol.accessToken(), postId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("LIKE_NOT_FOUND");
		// bob의 좋아요는 살아 있고, 카운터도 감소분이 롤백되어 1이어야 한다.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isEqualTo(1);
		assertThat(likeCountOf(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("없는 게시글에 좋아요하면 404이고, likes 행도 생기지 않는다")
	void rejectsLikeOnUnknownPost() throws Exception {
		Tokens bob = signupAndLogin("bob", PASSWORD);

		ResponseEntity<String> response = like(bob.accessToken(), 1L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("POST_NOT_FOUND");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isZero();
	}

	@Test
	@DisplayName("삭제된 게시글에 좋아요하면 404다 — 행은 남아 있어도 게시글은 없는 것이다")
	void rejectsLikeOnDeletedPost() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long postId = createPostId(alice.accessToken(), "지울 글");
		restTemplate.exchange("/api/v1/posts/" + postId, HttpMethod.DELETE,
				bearer(alice.accessToken()), String.class);

		ResponseEntity<String> response = like(bob.accessToken(), postId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes", Integer.class)).isZero();
	}

	@Test
	@DisplayName("토큰 없이 좋아요하면 401이다")
	void rejectsUnauthenticatedLike() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		long postId = createPostId(alice.accessToken(), "좋아요 받을 글");

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/v1/posts/" + postId + "/likes", null, String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(likeCountOf(postId)).isZero();
	}

	private int likeCountOf(long postId) {
		return jdbcTemplate.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, postId);
	}

	private ResponseEntity<String> like(String token, long postId) {
		return restTemplate.exchange("/api/v1/posts/" + postId + "/likes", HttpMethod.POST,
				bearer(token), String.class);
	}

	private ResponseEntity<String> unlike(String token, long postId) {
		return restTemplate.exchange("/api/v1/posts/" + postId + "/likes", HttpMethod.DELETE,
				bearer(token), String.class);
	}

	private long createPostId(String token, String content) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> response = restTemplate.exchange("/api/v1/posts", HttpMethod.POST,
				new HttpEntity<>(Map.of("content", content), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		try {
			return objectMapper.readTree(response.getBody()).get("id").asLong();
		} catch (Exception e) {
			throw new IllegalStateException("게시글 준비 실패", e);
		}
	}
}
