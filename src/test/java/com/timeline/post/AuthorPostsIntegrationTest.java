package com.timeline.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
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
 * 작성자 글 목록 통합 테스트 — {@code GET /users/{userId}/posts} (작업 0.11 · 마스터 &sect;6).
 *
 * <p>확인하는 것은 세 가지다. (1) 커서로 페이지가 <strong>이어 붙고</strong>, (2) 그 순서가 최신순이며,
 * (3) 삭제된 글이 빠진다. 특히 (1)은 "각 페이지가 맞다"가 아니라 <strong>페이지를 전부 이어 붙였을 때
 * 중복도 누락도 없다</strong>로 검증한다 — 커서 경계를 {@code <}가 아닌 {@code <=}로 잘못 쓰면
 * 페이지마다 항목 하나가 겹치는데, 페이지 단위로만 보면 그게 정상처럼 보인다.
 */
class AuthorPostsIntegrationTest extends IntegrationTestSupport {

	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("커서를 따라가면 최신순으로 중복·누락 없이 전부 읽히고, 마지막 페이지는 hasNext=false다")
	void paginatesByCursor() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		List<Long> writtenIds = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			writtenIds.add(createPostId(alice.accessToken(), "글 " + i));
		}

		// 첫 페이지 — 커서 없이 시작한다.
		JsonNode first = readAuthorPosts(alice, alice.userId(), null, 2);
		assertThat(idsOf(first)).containsExactly(writtenIds.get(4), writtenIds.get(3));
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		assertThat(first.get("nextCursor").asLong()).isEqualTo(writtenIds.get(3));

		JsonNode second = readAuthorPosts(alice, alice.userId(), first.get("nextCursor").asLong(), 2);
		assertThat(idsOf(second)).containsExactly(writtenIds.get(2), writtenIds.get(1));
		assertThat(second.get("hasNext").asBoolean()).isTrue();

		JsonNode third = readAuthorPosts(alice, alice.userId(), second.get("nextCursor").asLong(), 2);
		assertThat(idsOf(third)).containsExactly(writtenIds.get(0));
		// 남은 것이 없으면 커서도 없다.
		assertThat(third.get("hasNext").asBoolean()).isFalse();
		assertThat(third.get("nextCursor").isNull()).isTrue();

		// 세 페이지를 이어 붙이면 작성 역순 전체와 정확히 같아야 한다.
		List<Long> traversed = new ArrayList<>();
		traversed.addAll(idsOf(first));
		traversed.addAll(idsOf(second));
		traversed.addAll(idsOf(third));
		assertThat(traversed).containsExactly(
				writtenIds.get(4), writtenIds.get(3), writtenIds.get(2), writtenIds.get(1), writtenIds.get(0));
	}

	@Test
	@DisplayName("삭제된 글은 목록에서 빠지고, 그 자리를 다음 글이 메운다")
	void excludesDeletedPosts() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		List<Long> writtenIds = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			writtenIds.add(createPostId(alice.accessToken(), "글 " + i));
		}
		restTemplate.exchange("/api/v1/posts/" + writtenIds.get(2), HttpMethod.DELETE,
				bearer(alice.accessToken()), String.class);

		JsonNode page = readAuthorPosts(alice, alice.userId(), null, 20);

		// 최신 글을 지웠으므로 두 번째로 최신인 글이 맨 앞으로 온다 — 빈 자리로 남지 않는다.
		assertThat(idsOf(page)).containsExactly(writtenIds.get(1), writtenIds.get(0));
		assertThat(page.get("hasNext").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("목록에는 그 작성자의 글만 담기고, 응답 형식은 data/nextCursor/hasNext다")
	void returnsOnlyRequestedAuthorPostsInCursorFormat() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		long alicePostId = createPostId(alice.accessToken(), "앨리스의 글");
		createPostId(bob.accessToken(), "밥의 글");

		// 조회 주체는 bob이다 — 남의 글 목록도 볼 수 있어야 한다(공개 타임라인 서비스).
		JsonNode page = readAuthorPosts(bob, alice.userId(), null, 20);

		List<String> fieldNames = new ArrayList<>();
		page.fieldNames().forEachRemaining(fieldNames::add);
		assertThat(fieldNames).containsExactlyInAnyOrder("data", "nextCursor", "hasNext");
		assertThat(idsOf(page)).containsExactly(alicePostId);
		JsonNode item = page.get("data").get(0);
		assertThat(item.get("authorId").asLong()).isEqualTo(alice.userId());
		assertThat(item.get("content").asText()).isEqualTo("앨리스의 글");
		assertThat(item.get("likeCount").asInt()).isZero();
		assertThat(page.get("nextCursor").isNull()).isTrue();
		assertThat(page.get("hasNext").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("글이 없는 사용자는 404가 아니라 빈 페이지다")
	void returnsEmptyPageForAuthorWithoutPosts() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		JsonNode page = readAuthorPosts(alice, alice.userId(), null, 20);

		assertThat(idsOf(page)).isEmpty();
		assertThat(page.get("hasNext").asBoolean()).isFalse();
		assertThat(page.get("nextCursor").isNull()).isTrue();
	}

	private JsonNode readAuthorPosts(Tokens requester, long authorId, Long cursor, int size) throws Exception {
		String url = "/api/v1/users/" + authorId + "/posts?size=" + size
				+ (cursor != null ? "&cursor=" + cursor : "");
		ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
				bearer(requester.accessToken()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return objectMapper.readTree(response.getBody());
	}

	private List<Long> idsOf(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("data").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	private long createPostId(String token, String content) throws Exception {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> response = restTemplate.exchange("/api/v1/posts", HttpMethod.POST,
				new HttpEntity<>(Map.of("content", content), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return objectMapper.readTree(response.getBody()).get("id").asLong();
	}
}
