package com.timeline.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Pull 타임라인 통합 테스트 — {@code GET /api/v1/timeline} (P1-06 · 마스터 &sect;8 Phase 1).
 *
 * <p>이 스위트가 지키는 것은 <strong>결과 집합의 정의</strong>다. Phase 2a에서 같은 URL의 뒤편이
 * Redis Sorted Set으로 바뀌고, Phase 2b의 동등성 테스트(&sect;9.4)가 "Pull과 Push가 같은 20건을 준다"를
 * 검사하게 된다. 그때 기준이 되는 쪽이 여기서 굳어지는 계약이다 —
 * 지금 self-follow가 빠지거나 커서 경계가 어긋나 있으면, 그 어긋남이 Push의 정답으로 승격된다.
 *
 * <p>특히 커서 검증은 페이지 단위가 아니라 <strong>전 페이지를 이어 붙인 결과</strong>로 한다.
 * 경계를 {@code <}가 아닌 {@code <=}로 잘못 쓰면 페이지마다 항목이 하나씩 겹치는데,
 * 페이지 하나만 보면 그게 정상처럼 보인다.
 *
 * <p><strong>{@link AutoConfigureObservability}가 필요한 이유.</strong> Spring Boot는 테스트에서
 * 메트릭 export를 기본으로 끈다({@code management.defaults.metrics.export.enabled=false}를 주입한다) —
 * 그러면 등록되는 레지스트리가 {@code SimpleMeterRegistry}뿐이라 {@code /actuator/prometheus} 엔드포인트가
 * 아예 만들어지지 않는다. P1-07의 검증 항목이 "prometheus에 구간 타이머가 노출되는가"이므로
 * 이 테스트만은 운영과 같은 조건에서 돌아야 한다.
 */
@AutoConfigureObservability
class TimelineIntegrationTest extends IntegrationTestSupport {

	private static final String PASSWORD = "password123";

	/** 응답 페이지 크기. 조회는 25건이지만 반환은 20건이다 (마스터 &sect;5-2 · 로드맵 4.3절). */
	private static final int PAGE_SIZE = 20;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("내가 쓴 글이 내 타임라인에 나온다 — self-follow 행이 있기 때문이다")
	void includesOwnPostsViaSelfFollow() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		long postId = createPostId(alice.accessToken(), "내 글");

		JsonNode page = readTimeline(alice, null, null);

		// §4.3의 self-follow 불변식이 실제로 Pull 결과를 바꾸는지를 보는 유일한 지점이다.
		// 가입 트랜잭션에서 self 행이 빠지면 여기서 빈 페이지가 된다.
		assertThat(idsOf(page)).containsExactly(postId);

		List<String> fieldNames = new ArrayList<>();
		page.fieldNames().forEachRemaining(fieldNames::add);
		assertThat(fieldNames).containsExactlyInAnyOrder("data", "nextCursor", "hasNext");

		JsonNode item = page.get("data").get(0);
		assertThat(item.get("authorId").asLong()).isEqualTo(alice.userId());
		assertThat(item.get("content").asText()).isEqualTo("내 글");
		assertThat(item.get("likeCount").asInt()).isZero();
		assertThat(item.get("createdAt").asText()).isNotBlank();
	}

	@Test
	@DisplayName("팔로우한 사용자의 글은 나오고, 팔로우하지 않은 사용자의 글은 나오지 않는다")
	void includesFollowedAuthorsOnly() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		Tokens carol = signupAndLogin("carol", PASSWORD);
		follow(alice, bob.userId());

		long bobPostId = createPostId(bob.accessToken(), "밥의 글");
		createPostId(carol.accessToken(), "캐럴의 글");

		assertThat(idsOf(readTimeline(alice, null, null))).containsExactly(bobPostId);
	}

	@Test
	@DisplayName("삭제된 글은 타임라인에서 빠지고, 그 자리를 다음 글이 메운다")
	void excludesDeletedPosts() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());
		List<Long> bobPostIds = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			bobPostIds.add(createPostId(bob.accessToken(), "밥의 글 " + i));
		}

		// soft delete — 행은 남고 is_deleted만 선다. SQL의 is_deleted = false가 이걸 걸러야 한다.
		ResponseEntity<Void> deleted = restTemplate.exchange("/api/v1/posts/" + bobPostIds.get(1),
				HttpMethod.DELETE, bearer(bob.accessToken()), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		// 가운데 글을 지웠으므로 앞뒤 두 건이 붙어서 나온다 — 빈 자리로 남지 않는다.
		assertThat(idsOf(readTimeline(alice, null, null)))
				.containsExactly(bobPostIds.get(2), bobPostIds.get(0));
	}

	@Test
	@DisplayName("커서를 따라가면 최신순으로 중복·누락 없이 전부 읽히고, 마지막 페이지는 hasNext=false다")
	void paginatesByCursorWithoutDuplicatesOrGaps() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());

		// 26건 — 조회 상한(LIMIT 25)과 페이지 크기(20) 양쪽을 모두 넘긴다.
		// 25로 맞추면 "25건을 다 반환한다" 같은 버그가 페이지 경계에 숨을 수 있다.
		List<Long> writtenIds = new ArrayList<>();
		for (int i = 1; i <= 26; i++) {
			Tokens author = (i % 2 == 0) ? alice : bob;
			writtenIds.add(createPostId(author.accessToken(), "글 " + i));
		}

		// size를 주지 않는다 — 기본값이 20임을 이 호출이 함께 증명한다.
		JsonNode first = readTimeline(alice, null, null);
		assertThat(idsOf(first)).hasSize(PAGE_SIZE);
		assertThat(first.get("hasNext").asBoolean()).isTrue();

		List<Long> traversed = new ArrayList<>(idsOf(first));
		JsonNode page = first;
		// 커서를 따라 끝까지 간다. 상한을 둬 무한 루프가 테스트를 매달지 않게 한다.
		for (int guard = 0; page.get("hasNext").asBoolean() && guard < 10; guard++) {
			// 다음 커서는 직전 페이지의 마지막(=20번째) postId다.
			assertThat(page.get("nextCursor").asLong()).isEqualTo(traversed.get(traversed.size() - 1));
			page = readTimeline(alice, page.get("nextCursor").asLong(), null);
			traversed.addAll(idsOf(page));
		}

		assertThat(page.get("hasNext").asBoolean()).isFalse();
		assertThat(page.get("nextCursor").isNull()).isTrue();
		// 이어 붙인 결과가 작성 역순 전체와 정확히 같아야 한다 — 중복도 누락도 순서 뒤바뀜도 여기서 걸린다.
		List<Long> newestFirst = new ArrayList<>(writtenIds);
		Collections.reverse(newestFirst);
		assertThat(traversed).containsExactlyElementsOf(newestFirst);
	}

	@Test
	@DisplayName("정확히 20건이면 hasNext=false다 — 21번째 행이 없기 때문이다")
	void hasNextIsFalseAtExactlyOnePage() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());
		for (int i = 1; i <= PAGE_SIZE; i++) {
			createPostId(bob.accessToken(), "글 " + i);
		}

		JsonNode page = readTimeline(alice, null, null);

		assertThat(page.get("data")).hasSize(PAGE_SIZE);
		// LIMIT 25로 읽어도 20건뿐이라 21번째가 없다. hasNext를 "25건 읽혔는가"로 판정하면 여기가 통과하고
		// 아래 21건 케이스가 깨진다 — 두 테스트가 짝이다.
		assertThat(page.get("hasNext").asBoolean()).isFalse();
		assertThat(page.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("21건이면 첫 페이지 hasNext=true이고, nextCursor로 받은 2페이지에 나머지 1건이 있다")
	void hasNextIsTrueAtOneMoreThanPage() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());
		List<Long> writtenIds = new ArrayList<>();
		for (int i = 1; i <= PAGE_SIZE + 1; i++) {
			writtenIds.add(createPostId(bob.accessToken(), "글 " + i));
		}

		// size=50을 요청해도 20으로 깎인다 — 상한은 거절이 아니라 절삭이다(TimelineService.PAGE_SIZE).
		JsonNode first = readTimeline(alice, null, 50);

		assertThat(idsOf(first)).hasSize(PAGE_SIZE);
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		// 20번째 행(=가장 오래된 글에서 두 번째)의 postId가 커서다.
		assertThat(first.get("nextCursor").asLong()).isEqualTo(writtenIds.get(1));

		JsonNode second = readTimeline(alice, first.get("nextCursor").asLong(), null);
		assertThat(idsOf(second)).containsExactly(writtenIds.get(0));
		assertThat(second.get("hasNext").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("토큰 없이 타임라인을 요청하면 401이다")
	void rejectsUnauthenticatedRequest() {
		// 타임라인의 주체는 토큰이다(TimelineController). 미인증 요청이 통과하면
		// userId가 null인 채로 SQL에 들어간다.
		assertThat(restTemplate.getForEntity("/api/v1/timeline", String.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("두 구간 타이머가 /actuator/prometheus에 히스토그램과 함께 노출된다")
	void exposesSegmentTimersOnPrometheusEndpoint() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		createPostId(alice.accessToken(), "계측용 글");
		readTimeline(alice, null, null);

		ResponseEntity<String> scrape = restTemplate.getForEntity("/actuator/prometheus", String.class);
		assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
		String body = scrape.getBody();

		// P1-07의 검증 항목: 요청 전체 / DB 조회 두 구간이 각각 보여야
		// "클라이언트 지연 − 서버 내부 지연 = 큐잉 시간"(§9.3) 분해가 성립한다.
		assertThat(body).contains("timeline_request_seconds_count");
		assertThat(body).contains("timeline_pull_query_seconds_count");
		// 버킷이 없으면 Prometheus에서 p50/p99를 계산할 수 없다 — count/sum만으로는 평균밖에 안 나온다.
		assertThat(body).contains("timeline_request_seconds_bucket");
		assertThat(body).contains("timeline_pull_query_seconds_bucket");
		// 맨 바깥 경계(서블릿)는 액추에이터가 자동으로 붙인다. 이것까지 있어야 세 겹이 완성된다.
		assertThat(body).contains("uri=\"/api/v1/timeline\"");
	}

	private JsonNode readTimeline(Tokens requester, Long cursor, Integer size) throws Exception {
		StringBuilder url = new StringBuilder("/api/v1/timeline");
		String separator = "?";
		if (cursor != null) {
			url.append(separator).append("cursor=").append(cursor);
			separator = "&";
		}
		if (size != null) {
			url.append(separator).append("size=").append(size);
		}

		ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
				bearer(requester.accessToken()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return objectMapper.readTree(response.getBody());
	}

	private List<Long> idsOf(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("data").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	private void follow(Tokens follower, long targetUserId) {
		ResponseEntity<Void> response = restTemplate.exchange("/api/v1/users/" + targetUserId + "/follow",
				HttpMethod.POST, bearer(follower.accessToken()), Void.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
