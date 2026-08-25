package com.timeline.datagen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계층·코호트 배정 검증 (로드맵 P1-02 검증 열).
 *
 * <p>300만 행을 넣어 보고 확인할 일이 아니다. 축소 입력(사용자 1,000 / 관계 3만)에서
 * 분포 게이트 두 개 — <b>코호트 팔로잉 수 정확 일치</b>와 <b>계층 팔로워 합계 ±0.1%</b> — 를
 * 즉시 확인한다. 이 테스트가 깨지면 풀 스케일 적재는 시작할 가치가 없다.
 */
class DistributionPlannerTest {

	private static SeedSpec spec;
	private static FollowPlan plan;
	/** achieved[id] = 실제로 배정된 팔로워 수. */
	private static int[] achieved;

	@BeforeAll
	static void planOnce() {
		spec = SeedSpec.smoke();
		plan = DistributionPlanner.plan(spec);

		achieved = new int[spec.users() + 1];
		forEachPair((follower, followee) -> achieved[followee]++);
	}

	@Test
	@DisplayName("총 관계 수가 명세와 정확히 같다 (슬롯 풀이 남지도 모자라지도 않는다)")
	void totalMatchesSpec() {
		assertThat(plan.totalPairs()).isEqualTo((int) spec.followTotal());
	}

	@Test
	@DisplayName("코호트 팔로잉 수는 정확 일치한다 — 측정 시나리오의 전제라 오차를 허용하지 않는다")
	void cohortFollowingCountsAreExact() {
		int id = 1;
		for (SeedSpec.Group cohort : spec.measurementCohorts()) {
			for (int m = 0; m < cohort.members(); m++, id++) {
				assertThat(plan.followingCount(id))
						.as("코호트 %s의 사용자 %d", cohort.name(), id)
						.isEqualTo(cohort.perUser());
			}
		}
	}

	@Test
	@DisplayName("계층별 팔로워 합계가 명세의 ±0.1% 이내다")
	void tierFollowerSumsAreWithinTolerance() {
		Map<String, Long> sums = new HashMap<>();
		Map<String, Integer> members = new HashMap<>();
		for (int id = 1; id <= plan.users(); id++) {
			sums.merge(plan.tierOf(id), (long) achieved[id], Long::sum);
			members.merge(plan.tierOf(id), 1, Integer::sum);
		}

		for (SeedSpec.Group tier : spec.tiers()) {
			assertThat(members.get(tier.name()))
					.as("계층 %s 인원", tier.name())
					.isEqualTo(tier.members());

			long expected = tier.total();
			long actual = sums.getOrDefault(tier.name(), 0L);
			double tolerance = Math.max(1, expected * 0.001);
			assertThat(Math.abs(actual - expected))
					.as("계층 %s 팔로워 합계 (기대 %d / 실제 %d)", tier.name(), expected, actual)
					.isLessThanOrEqualTo((long) tolerance);
		}
	}

	@Test
	@DisplayName("고정 계층(S/A/B)은 1인당 팔로워 수까지 정확하다")
	void fixedTierMembersHitTheirTargetExactly() {
		for (int id = 1; id <= plan.users(); id++) {
			assertThat(achieved[id])
					.as("사용자 %d (계층 %s)", id, plan.tierOf(id))
					.isEqualTo(plan.followerTarget(id));
		}
	}

	@Test
	@DisplayName("self-follow도 중복 팔로우도 없다")
	void noSelfAndNoDuplicatePairs() {
		Set<Long> pairs = new HashSet<>(plan.totalPairs() * 2);
		forEachPair((follower, followee) -> {
			assertThat(followee).as("self-follow가 실관계에 섞였다 (%d)", follower).isNotEqualTo(follower);
			assertThat(pairs.add(((long) follower << 32) | followee))
					.as("중복 팔로우 (%d → %d)", follower, followee)
					.isTrue();
		});
		assertThat(pairs).hasSize(plan.totalPairs());
	}

	@Test
	@DisplayName("같은 시드면 같은 분포가 재현된다")
	void sameSeedReproducesSameAssignment() {
		FollowPlan again = DistributionPlanner.plan(SeedSpec.smoke());

		assertThat(again.totalPairs()).isEqualTo(plan.totalPairs());
		for (int id = 1; id <= plan.users(); id++) {
			assertThat(again.followerTarget(id)).isEqualTo(plan.followerTarget(id));
			assertThat(again.followingCount(id)).isEqualTo(plan.followingCount(id));
			for (int k = 0; k < again.followingCount(id); k++) {
				assertThat(again.followeeAt(id, k)).isEqualTo(plan.followeeAt(id, k));
			}
		}
	}

	@Test
	@DisplayName("두 축의 합계가 다른 명세는 만들어지지 않는다")
	void mismatchedAxisTotalsAreRejected() {
		assertThatThrownBy(() -> new SeedSpec(
				"broken",
				100,
				List.of(SeedSpec.Group.fixed("S", 1, 50), SeedSpec.Group.spread("C", 99, 950)),
				List.of(SeedSpec.Group.fixed("heavy", 1, 10), SeedSpec.Group.spread("rest", 99, 500)),
				0,
				1L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("두 축의 합계가 달라");
	}

	private interface PairConsumer {
		void accept(int follower, int followee);
	}

	private static void forEachPair(PairConsumer consumer) {
		for (int follower = 1; follower <= plan.users(); follower++) {
			for (int k = 0; k < plan.followingCount(follower); k++) {
				consumer.accept(follower, plan.followeeAt(follower, k));
			}
		}
	}
}
