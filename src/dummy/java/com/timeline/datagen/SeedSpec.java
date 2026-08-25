package com.timeline.datagen;

import java.util.List;

/**
 * 더미 데이터 규모 명세 — 마스터 &sect;8 Phase 1의 두 분포표를 그대로 옮긴 값 객체다.
 *
 * <p>두 축은 <b>독립</b>이다. 팔로워 축(계층 S/A/B/C)은 fan-out 부하를 정하고,
 * 팔로잉 축(코호트 heavy/normal/light)은 Pull 조회 비용을 정한다.
 * 같은 사용자가 S계층이면서 heavy 코호트일 수 있고, 그건 의도된 것이다.
 *
 * <p><b>두 축의 합계는 반드시 같아야 한다.</b> 팔로워 축의 합은 "생성될 (follower, followee) 쌍의
 * followee 쪽 총량"이고 팔로잉 축의 합은 같은 쌍의 follower 쪽 총량이라, 둘은 같은 수를 다르게 센 것이다.
 * 어긋나면 슬롯 풀이 남거나 모자라므로 생성 자체가 성립하지 않는다 — 생성자에서 막는다.
 *
 * <p>코호트 소속은 <b>사용자 id 구간으로 고정</b>한다(heavy = 1..N₁, normal = 그다음 N₂개, ...).
 * 어떤 사용자가 어떤 코호트인지를 RNG에 맡기면 검증 쿼리가 그 배정을 재현할 방법이 없어서,
 * "코호트 팔로잉 수 정확 일치"라는 게이트(로드맵 &sect;4.1)를 SQL만으로 검사할 수 없게 된다.
 * 반면 계층은 무작위 배정이다 — 두 축의 독립성이 여기서 나온다.
 *
 * @param name       스케일 이름 (로그·검증 스크립트 인자)
 * @param users      사용자 수
 * @param tiers      팔로워 축. 마지막 원소는 반드시 분산 그룹({@link Group#isSpread()})이다
 * @param cohorts    팔로잉 축. 앞쪽 고정 그룹들이 측정 코호트이고 마지막은 배경 사용자다
 * @param posts      게시글 수
 * @param randomSeed 고정 RNG 시드 — 같은 명세면 같은 분포가 재현된다(로드맵 &sect;4.7)
 */
public record SeedSpec(
		String name,
		int users,
		List<Group> tiers,
		List<Group> cohorts,
		long posts,
		long randomSeed) {

	/**
	 * 분포표의 한 행.
	 *
	 * <p>고정 그룹({@code perUser >= 0})은 1인당 수가 정확히 지켜지고,
	 * 분산 그룹({@code perUser < 0})은 1인당 수에 편차를 주되 <b>합계만 정확히</b> 맞춘다.
	 *
	 * @param members 인원
	 * @param perUser 1인당 수. 음수면 분산 그룹이다
	 * @param total   그룹 합계
	 */
	public record Group(String name, int members, int perUser, long total) {

		/** 1인당 수가 고정인 그룹 (S/A/B, heavy/normal/light). */
		public static Group fixed(String name, int members, int perUser) {
			return new Group(name, members, perUser, (long) members * perUser);
		}

		/** 합계만 맞추고 1인당 수는 흩뿌리는 그룹 (계층 C, 배경 사용자). */
		public static Group spread(String name, int members, long total) {
			return new Group(name, members, -1, total);
		}

		public boolean isSpread() {
			return perUser < 0;
		}
	}

	public SeedSpec {
		if (users < 2) {
			throw new IllegalArgumentException("사용자는 2명 이상이어야 한다: " + users);
		}
		if (posts < 0) {
			throw new IllegalArgumentException("게시글 수는 음수일 수 없다: " + posts);
		}
		validateAxis("팔로워 축(계층)", tiers, users);
		validateAxis("팔로잉 축(코호트)", cohorts, users);

		long tierTotal = sumTotal(tiers);
		long cohortTotal = sumTotal(cohorts);
		if (tierTotal != cohortTotal) {
			throw new IllegalArgumentException(
					"두 축의 합계가 달라 슬롯 풀이 성립하지 않는다: 팔로워 축 " + tierTotal + " vs 팔로잉 축 " + cohortTotal);
		}
	}

	private static void validateAxis(String axis, List<Group> groups, int users) {
		if (groups.isEmpty() || !groups.get(groups.size() - 1).isSpread()) {
			throw new IllegalArgumentException(axis + "의 마지막 그룹은 분산 그룹이어야 한다");
		}
		long members = 0;
		for (Group g : groups) {
			if (g.members() <= 0) {
				throw new IllegalArgumentException(axis + " " + g.name() + ": 인원이 0 이하다");
			}
			members += g.members();
			// 자기 자신은 셀 수 없으므로 1인당 상한은 users-1 이다. 분산 그룹은 평균의 2배를 상한으로 본다
			// (DistributionPlanner가 [1, 2*평균-1] 범위 가중치로 흩뿌린다).
			long cap = g.isSpread() ? 2 * Math.max(1, g.total() / g.members()) : g.perUser();
			if (cap > users - 1L) {
				throw new IllegalArgumentException(
						axis + " " + g.name() + ": 1인당 " + cap + "은 전체 사용자 " + users + "명으로 만들 수 없다");
			}
		}
		if (members != users) {
			throw new IllegalArgumentException(
					axis + "의 인원 합 " + members + "이 사용자 수 " + users + "와 다르다");
		}
	}

	private static long sumTotal(List<Group> groups) {
		long sum = 0;
		for (Group g : groups) {
			sum += g.total();
		}
		return sum;
	}

	/** 생성할 실팔로우 관계 수 (self-follow는 여기 포함되지 않는다 — FollowSeeder가 따로 넣는다). */
	public long followTotal() {
		return sumTotal(tiers);
	}

	/** 측정 코호트 = 팔로잉 축의 고정 그룹들 (heavy/normal/light). 배경 사용자는 제외된다. */
	public List<Group> measurementCohorts() {
		return cohorts.stream().filter(g -> !g.isSpread()).toList();
	}

	/**
	 * 풀 스케일 — 마스터 &sect;8 Phase 1 분포표 그대로.
	 * 사용자 10만 / 실팔로우 300만 / 게시글 300만.
	 */
	public static SeedSpec full() {
		return new SeedSpec(
				"full",
				100_000,
				List.of(
						Group.fixed("S", 10, 20_000),
						Group.fixed("A", 100, 5_000),
						Group.fixed("B", 1_000, 500),
						Group.spread("C", 98_890, 1_800_000)),
				List.of(
						Group.fixed("heavy", 1_000, 500),
						Group.fixed("normal", 2_000, 100),
						Group.fixed("light", 2_000, 10),
						Group.spread("rest", 95_000, 2_280_000)),
				3_000_000,
				RANDOM_SEED);
	}

	/**
	 * 축소 스케일 — 사용자 1,000 / 실팔로우 3만 / 게시글 3만. 스모크 적재와 단위 테스트가 쓴다.
	 *
	 * <p>코호트 팔로잉 수(500/100/10)는 풀 스케일과 <b>같게</b> 둔다. 이 값이 "정확 일치" 게이트의
	 * 대상이라, 축소판에서 값을 바꾸면 스모크가 검증하는 게 실제 게이트가 아니게 된다.
	 * 대신 인원을 1/100로 줄여 합계를 맞췄다.
	 *
	 * <p>S계층은 1인당 900명이다 — 20,000의 1/100인 200이 아니라. 사용자가 1,000명뿐이라
	 * 1인당 팔로워 상한이 999이고, "상위 계층은 상한에 가깝다"는 성질을 유지하려면 200보다 900이 맞다.
	 */
	public static SeedSpec smoke() {
		return new SeedSpec(
				"smoke",
				1_000,
				List.of(
						Group.fixed("S", 2, 900),
						Group.fixed("A", 10, 500),
						Group.fixed("B", 50, 100),
						Group.spread("C", 938, 18_200)),
				List.of(
						Group.fixed("heavy", 10, 500),
						Group.fixed("normal", 20, 100),
						Group.fixed("light", 20, 10),
						Group.spread("rest", 950, 22_800)),
				30_000,
				RANDOM_SEED);
	}

	/** 고정 RNG 시드. 값 자체에 의미는 없고, 바뀌지 않는다는 사실에만 의미가 있다. */
	private static final long RANDOM_SEED = 20_260_907L;

	public static SeedSpec of(String scale) {
		return switch (scale) {
			case "full" -> full();
			case "smoke" -> smoke();
			default -> throw new IllegalArgumentException("알 수 없는 스케일: " + scale + " (full | smoke)");
		};
	}
}
