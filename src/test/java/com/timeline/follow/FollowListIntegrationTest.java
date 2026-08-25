package com.timeline.follow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 팔로워/팔로잉 목록 통합 테스트 (작업 0.10 · 마스터 &sect;4.3 &sect;6).
 *
 * <p>이 클래스가 지키는 것은 <strong>self-follow 행이 사용자에게 보이지 않는다</strong>는 것이다.
 * 그 행은 Pull/Push의 결과 집합을 맞추려고 시스템이 넣은 것이지 사용자가 만든 관계가 아니다(&sect;4.3).
 * 쿼리의 {@code follower_id != followee_id} 한 줄이 빠지면 모든 사용자의 목록에 본인이 섞이는데,
 * 그건 Phase 1~3 내내 조용히 남는 종류의 버그다.
 *
 * <p>나머지 절반은 커서 페이지네이션의 계약이다 — {@code data}/{@code nextCursor}/{@code hasNext}
 * 형식(&sect;6)과, 남은 항목이 없을 때 {@code hasNext}가 false가 되는 경계.
 */
class FollowListIntegrationTest extends IntegrationTestSupport {

	private static final String PASSWORD = "password123";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("팔로워 목록에도 팔로잉 목록에도 본인(self-follow 행)은 나오지 않는다")
	void excludesSelfFollowRow() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());

		// bob의 팔로워는 alice 하나뿐 — follows에는 bob→bob 행도 있지만 목록에는 없어야 한다.
		assertThat(usernames(list(alice, bob.userId(), "followers", ""))).containsExactly("alice");
		// alice의 팔로잉은 bob 하나뿐 — alice→alice 행이 섞이면 안 된다.
		assertThat(usernames(list(alice, alice.userId(), "followings", ""))).containsExactly("bob");

		// DB에는 self-follow 행이 실제로 있다. 목록에서 빠진 것이지 삭제된 것이 아니다.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM follows WHERE follower_id = followee_id", Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("응답은 {data, nextCursor, hasNext} 형식이고 항목은 id·username·nickname을 담는다")
	void returnsCursorPageFormat() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());

		JsonNode page = list(alice, bob.userId(), "followers", "");

		assertThat(page.has("data")).isTrue();
		assertThat(page.has("nextCursor")).isTrue();
		assertThat(page.has("hasNext")).isTrue();
		assertThat(page.get("hasNext").asBoolean()).isFalse();
		assertThat(page.get("nextCursor").isNull()).isTrue();

		JsonNode item = page.get("data").get(0);
		assertThat(item.get("id").asLong()).isEqualTo(alice.userId());
		assertThat(item.get("username").asText()).isEqualTo("alice");
		assertThat(item.get("nickname").asText()).isEqualTo("alice");
		// 목록 항목에 비밀번호나 팔로워 수 같은 것이 딸려 나오면 안 된다.
		assertThat(item.size()).isEqualTo(3);
	}

	@Test
	@DisplayName("커서로 다음 페이지를 이어 읽는다 — 최신 팔로우가 먼저 나온다")
	void walksPagesWithCursor() throws Exception {
		Tokens bob = signupAndLogin("bob", PASSWORD);
		List<Tokens> followers = followBob(bob, "f1", "f2", "f3");

		JsonNode first = list(followers.get(0), bob.userId(), "followers", "?size=2");

		// follows.id DESC = 최근에 팔로우한 순서. f3 → f2 → f1이다.
		assertThat(usernames(first)).containsExactly("f3", "f2");
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		assertThat(first.get("nextCursor").isNull()).isFalse();

		JsonNode second = list(followers.get(0), bob.userId(), "followers",
				"?size=2&cursor=" + first.get("nextCursor").asLong());

		assertThat(usernames(second)).containsExactly("f1");
		assertThat(second.get("hasNext").asBoolean()).isFalse();
		assertThat(second.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("남은 항목이 정확히 size개면 hasNext는 false다 — 빈 다음 페이지를 만들지 않는다")
	void hasNextIsFalseWhenPageExactlyFits() throws Exception {
		Tokens bob = signupAndLogin("bob", PASSWORD);
		List<Tokens> followers = followBob(bob, "f1", "f2", "f3");

		JsonNode page = list(followers.get(0), bob.userId(), "followers", "?size=3");

		// size+1개를 읽어 판정하므로, 3개를 요청해 3개가 나오면 4번째가 없다는 뜻이다.
		assertThat(usernames(page)).containsExactly("f3", "f2", "f1");
		assertThat(page.get("hasNext").asBoolean()).isFalse();
		assertThat(page.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("아무도 없으면 빈 목록이다 — data는 [], hasNext는 false, nextCursor는 null")
	void returnsEmptyPage() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);

		// 가입 직후라 alice가 가진 팔로우 관계는 self-follow 행 하나뿐이고, 그건 제외 대상이다.
		for (String kind : List.of("followers", "followings")) {
			JsonNode page = list(alice, alice.userId(), kind, "");
			assertThat(page.get("data")).isEmpty();
			assertThat(page.get("hasNext").asBoolean()).isFalse();
			assertThat(page.get("nextCursor").isNull()).isTrue();
		}
	}

	@Test
	@DisplayName("팔로워 목록과 팔로잉 목록은 방향이 서로 반대다")
	void followersAndFollowingsPointInOppositeDirections() throws Exception {
		Tokens alice = signupAndLogin("alice", PASSWORD);
		Tokens bob = signupAndLogin("bob", PASSWORD);
		follow(alice, bob.userId());

		// alice → bob 한 방향만 있으므로, 목록도 한쪽에만 나와야 한다.
		assertThat(usernames(list(alice, bob.userId(), "followers", ""))).containsExactly("alice");
		assertThat(usernames(list(alice, alice.userId(), "followings", ""))).containsExactly("bob");
		// 반대편은 비어 있다. 쿼리의 followee_id/follower_id를 뒤집어 구현하면 여기서 걸린다.
		assertThat(usernames(list(alice, alice.userId(), "followers", ""))).isEmpty();
		assertThat(usernames(list(alice, bob.userId(), "followings", ""))).isEmpty();
	}

	/** {@code usernames} 순서대로 가입시켜 전원이 bob을 팔로우하게 만든다. */
	private List<Tokens> followBob(Tokens bob, String... usernames) {
		List<Tokens> followers = new ArrayList<>();
		for (String username : usernames) {
			Tokens follower = signupAndLogin(username, PASSWORD);
			follow(follower, bob.userId());
			followers.add(follower);
		}
		return followers;
	}

	private void follow(Tokens actor, Long targetUserId) {
		ResponseEntity<String> response = restTemplate.exchange("/api/v1/users/{userId}/follow", HttpMethod.POST,
				bearer(actor.accessToken()), String.class, targetUserId);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private JsonNode list(Tokens caller, Long userId, String kind, String query) throws Exception {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/v1/users/" + userId + "/" + kind + query,
				HttpMethod.GET, bearer(caller.accessToken()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return objectMapper.readTree(response.getBody());
	}

	private List<String> usernames(JsonNode page) {
		List<String> result = new ArrayList<>();
		page.get("data").forEach(item -> result.add(item.get("username").asText()));
		return result;
	}
}
