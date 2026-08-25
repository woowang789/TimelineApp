package com.timeline.post;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 게시글 작성·조회·삭제 통합 테스트 (작업 0.11 · 마스터 &sect;4.1, &sect;6).
 *
 * <p>이 클래스의 본론은 <strong>soft delete</strong>다. "삭제하면 안 보인다"까지는 어느 구현이든 통과하므로,
 * 여기서는 한 걸음 더 들어가 <strong>행이 DB에 그대로 남아 있고 {@code is_deleted}만 바뀌었는지</strong>를
 * jdbcTemplate으로 직접 확인한다. Phase 2a의 타임라인 Sorted Set에는 post_id만 들어가서
 * 행이 사라지면 그 id가 무엇이었는지 되짚을 수 없고, "25개 조회 → 삭제 필터 → 20개 반환"이라는
 * 페이지 규약 자체가 이 행의 존속을 전제로 한다.
 */
class PostIntegrationTest extends IntegrationTestSupport {

	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("작성하면 201 + Snowflake id가 부여되고, 같은 내용이 단건 조회로 돌아온다")
	void createsPostAndReadsItBack() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> created = createPost(alice.accessToken(), "첫 번째 글");

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		JsonNode body = objectMapper.readTree(created.getBody());
		long postId = body.get("id").asLong();
		// id는 DB AUTO_INCREMENT가 아니라 애플리케이션이 만든 Snowflake다 — 1이면 채번 경로가 바뀐 것이다.
		assertThat(postId).isPositive().isNotEqualTo(1L);
		assertThat(body.get("authorId").asLong()).isEqualTo(alice.userId());
		assertThat(body.get("content").asText()).isEqualTo("첫 번째 글");
		assertThat(body.get("likeCount").asInt()).isZero();
		assertThat(body.get("createdAt").asText()).isNotBlank();

		// 뒤에 쓴 글의 id가 더 커야 한다 — id 정렬 = 시간 정렬이 커서 페이지네이션의 전제다(§4.2).
		long secondId = objectMapper.readTree(createPost(alice.accessToken(), "두 번째 글").getBody())
				.get("id").asLong();
		assertThat(secondId).isGreaterThan(postId);

		ResponseEntity<String> fetched = restTemplate.exchange("/api/v1/posts/" + postId,
				HttpMethod.GET, bearer(alice.accessToken()), String.class);

		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode fetchedBody = objectMapper.readTree(fetched.getBody());
		assertThat(fetchedBody.get("id").asLong()).isEqualTo(postId);
		assertThat(fetchedBody.get("content").asText()).isEqualTo("첫 번째 글");
	}

	@Test
	@DisplayName("삭제하면 조회는 404지만 DB 행은 is_deleted = true로 남는다")
	void softDeletesPost() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		long postId = createPostId(alice.accessToken(), "지울 글");

		ResponseEntity<String> deleted = restTemplate.exchange("/api/v1/posts/" + postId,
				HttpMethod.DELETE, bearer(alice.accessToken()), String.class);

		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<String> fetched = restTemplate.exchange("/api/v1/posts/" + postId,
				HttpMethod.GET, bearer(alice.accessToken()), String.class);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(objectMapper.readTree(fetched.getBody()).get("code").asText()).isEqualTo("POST_NOT_FOUND");

		// 하드 삭제가 아니다 — 행도, 본문도 그대로 있고 플래그만 바뀌었다.
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT content, is_deleted FROM posts WHERE id = ?", postId);
		assertThat(row.get("content")).isEqualTo("지울 글");
		assertThat(row.get("is_deleted")).isEqualTo(true);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("남의 글을 삭제하려 하면 403이고, 그 글은 그대로 살아 있다")
	void rejectsDeleteByNonAuthor() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long postId = createPostId(alice.accessToken(), "앨리스의 글");

		ResponseEntity<String> response = restTemplate.exchange("/api/v1/posts/" + postId,
				HttpMethod.DELETE, bearer(bob.accessToken()), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("NOT_POST_AUTHOR");
		assertThat(jdbcTemplate.queryForObject("SELECT is_deleted FROM posts WHERE id = ?", Boolean.class, postId))
				.isFalse();
	}

	@Test
	@DisplayName("이미 삭제된 글을 다시 삭제하면 404다 — 없는 글과 구분하지 않는다")
	void rejectsDeleteOfDeletedPost() {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		long postId = createPostId(alice.accessToken(), "지울 글");
		restTemplate.exchange("/api/v1/posts/" + postId, HttpMethod.DELETE, bearer(alice.accessToken()), String.class);

		ResponseEntity<String> response = restTemplate.exchange("/api/v1/posts/" + postId,
				HttpMethod.DELETE, bearer(alice.accessToken()), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("없는 게시글 조회는 404다")
	void returnsNotFoundForUnknownPost() {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = restTemplate.exchange("/api/v1/posts/1",
				HttpMethod.GET, bearer(alice.accessToken()), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("토큰 없이 작성하면 401이고, 행은 생기지 않는다")
	void rejectsUnauthenticatedCreate() {
		ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/posts",
				Map.of("content", "토큰 없는 글"), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class)).isZero();
	}

	@Test
	@DisplayName("content가 501자면 400이다 — 상한은 VARCHAR(500)과 맞춘다")
	void rejectsTooLongContent() {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		ResponseEntity<String> response = createPost(alice.accessToken(), "가".repeat(501));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		// 500자였다면 통과했어야 한다는 것까지 확인해 둔다 — 경계가 501에 있음을 못 박는다.
		assertThat(createPost(alice.accessToken(), "가".repeat(500)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	@DisplayName("content가 공백뿐이면 400이다")
	void rejectsBlankContent() {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		assertThat(createPost(alice.accessToken(), "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class)).isZero();
	}

	private long createPostId(String token, String content) {
		try {
			return objectMapper.readTree(createPost(token, content).getBody()).get("id").asLong();
		} catch (Exception e) {
			throw new IllegalStateException("게시글 준비 실패", e);
		}
	}

	private ResponseEntity<String> createPost(String token, String content) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange("/api/v1/posts", HttpMethod.POST,
				new HttpEntity<>(Map.of("content", content), headers), String.class);
	}
}
