package com.timeline.datagen;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 계층·코호트 배정과 팔로우 쌍 생성 (P1-02). <b>순수 로직이다 — JDBC를 모른다.</b>
 *
 * <p>DB를 모르게 만든 이유는 이 클래스가 단위 테스트의 대상이기 때문이다(로드맵 P1-02 검증).
 * 분포가 맞는지는 300만 행을 넣어 보고 확인할 일이 아니라 1,000명 축소 입력에서 즉시 확인할 일이다.
 *
 * <h2>왜 슬롯 풀인가</h2>
 * 팔로워 축과 팔로잉 축을 <b>동시에</b> 맞춰야 한다. 팔로잉 쪽만 보고 "follower마다 랜덤 followee를
 * 뽑는다"고 하면 팔로워 분포가 균등해져 S계층(2만 팔로워)이 생기지 않고, 팔로워 쪽만 보고 채우면
 * 코호트 팔로잉 수가 흐트러진다. 그래서 <b>followee를 목표 팔로워 수만큼 슬롯으로 복제한 풀</b>을 만들고
 * 셔플한 뒤, follower가 자기 쿼터만큼 앞에서부터 가져간다. 풀 슬롯이 정확히 한 번씩 소비되므로
 * 두 축의 합계가 <b>동시에, 정확히</b> 지켜진다.
 *
 * <h2>충돌 보정 — 2단계</h2>
 * 뽑은 슬롯이 자기 자신이거나 이미 팔로우한 상대면 쓸 수 없다.
 * <ol>
 *   <li><b>전방 스왑</b> — 커서 앞쪽에서 쓸 수 있는 슬롯을 찾아 커서 위치와 맞바꾼다.
 *       풀이 셔플되어 있어 보통 한두 칸 안에서 끝난다</li>
 *   <li><b>기배정 스왑</b>(수렴 보정) — 풀 끝에서는 남은 슬롯이 전부 중복일 수 있다. 이때는
 *       이미 배정을 마친 다른 follower f2를 무작위로 골라, f2의 followee e2와 지금 슬롯 e를 맞바꾼다
 *       (f2가 e를 가져가고 f가 e2를 가져간다). 교환이므로 <b>어느 쪽 합계도 변하지 않는다</b> —
 *       이것이 없으면 마지막 몇 명에서 배정이 수렴하지 않는다</li>
 * </ol>
 * 두 보정 모두 슬롯을 버리지 않으므로 계층 팔로워 합계는 ±0.1%가 아니라 <b>정확히</b> 일치한다.
 */
public final class DistributionPlanner {

	private DistributionPlanner() {
	}

	public static FollowPlan plan(SeedSpec spec) {
		return new Run(spec).execute();
	}

	/** 기배정 스왑 시도 상한. 이 안에서 못 찾으면 분포 명세 자체가 불가능한 경우다. */
	private static final int SWAP_ATTEMPT_LIMIT = 20_000;

	private static final class Run {

		private final SeedSpec spec;
		private final int n;
		private final Random rnd;

		private final int[] followerTarget;
		private final byte[] tierIndex;
		private final int[] followingQuota;

		private int[] pool;
		private int cursor;

		private int[] offset;
		private int[] followees;

		/** takenMark[e] == token 이면 "지금 처리 중인 follower가 이미 e를 가져갔다". 매번 초기화하지 않으려는 장치다. */
		private final int[] takenMark;
		private int token;

		private final int[] processed;
		private int processedCount;

		private long swapFixups;

		Run(SeedSpec spec) {
			this.spec = spec;
			this.n = spec.users();
			this.rnd = new Random(spec.randomSeed());
			this.followerTarget = new int[n + 1];
			this.tierIndex = new byte[n + 1];
			this.followingQuota = new int[n + 1];
			this.takenMark = new int[n + 1];
			this.processed = new int[n];
		}

		FollowPlan execute() {
			long total = spec.followTotal();
			if (total > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("팔로우 관계가 int 범위를 넘는다: " + total);
			}

			// 1) 팔로워 축 — 무작위 순열에 계층을 얹는다. 코호트가 id 구간 고정이므로,
			//    여기서 무작위로 섞어야 두 축이 독립이 된다.
			assignAxis(spec.tiers(), shuffledIds(), followerTarget, tierIndex);

			// 2) 팔로잉 축 — id 오름차순. heavy = 1..N₁, normal = 그다음 N₂개, ... (SeedSpec 주석 참조)
			assignAxis(spec.cohorts(), ascendingIds(), followingQuota, null);

			buildPool((int) total);
			buildOffsets();
			assignPairs();

			// follower별 구간을 followee 오름차순으로 정렬한다. 배정 순서에는 의미가 없고,
			// 정렬해 두면 적재가 UNIQUE (follower_id, followee_id) 인덱스에 순차로 들어간다(FollowPlan 주석).
			for (int u = 1; u <= n; u++) {
				Arrays.sort(followees, offset[u], offset[u + 1]);
			}

			String[] tierNames = spec.tiers().stream().map(SeedSpec.Group::name).toArray(String[]::new);
			return new FollowPlan(n, offset, followees, followerTarget, tierIndex, tierNames, swapFixups);
		}

		/** 그룹 목록을 {@code order}가 정한 순서대로 사용자에게 배정한다. */
		private void assignAxis(List<SeedSpec.Group> groups, int[] order, int[] out, byte[] groupOut) {
			int at = 0;
			for (int gi = 0; gi < groups.size(); gi++) {
				SeedSpec.Group g = groups.get(gi);
				if (g.isSpread()) {
					int[] quotas = spreadQuotas(g.members(), g.total());
					for (int m = 0; m < g.members(); m++) {
						int id = order[at++];
						out[id] = quotas[m];
						if (groupOut != null) {
							groupOut[id] = (byte) gi;
						}
					}
				} else {
					for (int m = 0; m < g.members(); m++) {
						int id = order[at++];
						out[id] = g.perUser();
						if (groupOut != null) {
							groupOut[id] = (byte) gi;
						}
					}
				}
			}
		}

		/**
		 * 합계는 정확히 {@code total}, 1인당은 흩어진 수열을 만든다.
		 *
		 * <p>[1, 2·평균-1] 범위의 가중치를 뽑고 비례 배분한 뒤, 버림으로 남은 나머지를 1씩 나눠 준다.
		 * 버림 오차가 항마다 1 미만이므로 나머지는 항상 인원 수보다 작다 — 한 명당 최대 +1이다.
		 * 상한을 평균의 2배로 잡은 건 계층 C가 B의 1인당(500)을 넘지 않게 하려는 것이기도 하다.
		 * 넘으면 "팔로워 수 상위 N명 = 상위 계층"이라는 검증 쿼리의 전제가 깨진다.
		 */
		private int[] spreadQuotas(int members, long total) {
			long avg = Math.max(1, total / members);
			int hi = (int) Math.max(1, 2 * avg - 1);

			int[] weights = new int[members];
			long weightSum = 0;
			for (int i = 0; i < members; i++) {
				weights[i] = 1 + rnd.nextInt(hi);
				weightSum += weights[i];
			}

			int[] quotas = new int[members];
			long assigned = 0;
			for (int i = 0; i < members; i++) {
				quotas[i] = (int) (weights[i] * total / weightSum);
				assigned += quotas[i];
			}

			long remain = total - assigned;
			int at = rnd.nextInt(members);
			while (remain > 0) {
				quotas[at]++;
				remain--;
				at = (at + 1) % members;
			}
			return quotas;
		}

		private void buildPool(int total) {
			pool = new int[total];
			int at = 0;
			for (int u = 1; u <= n; u++) {
				for (int k = 0; k < followerTarget[u]; k++) {
					pool[at++] = u;
				}
			}
			for (int i = total - 1; i > 0; i--) {
				int j = rnd.nextInt(i + 1);
				int tmp = pool[i];
				pool[i] = pool[j];
				pool[j] = tmp;
			}
		}

		private void buildOffsets() {
			offset = new int[n + 2];
			for (int u = 1; u <= n; u++) {
				offset[u + 1] = offset[u] + followingQuota[u];
			}
			followees = new int[offset[n + 1]];
		}

		private void assignPairs() {
			int[] order = shuffledIds();
			for (int oi = 0; oi < n; oi++) {
				int follower = order[oi];
				token++;
				int base = offset[follower];
				int quota = followingQuota[follower];
				for (int k = 0; k < quota; k++) {
					int followee = takeSlot(follower);
					followees[base + k] = followee;
					takenMark[followee] = token;
				}
				processed[processedCount++] = follower;
			}
			if (cursor != pool.length) {
				throw new IllegalStateException("풀 슬롯이 남았다: " + (pool.length - cursor));
			}
		}

		private int takeSlot(int follower) {
			if (acceptable(pool[cursor], follower)) {
				return pool[cursor++];
			}
			// 전방 스왑 — 셔플된 풀이라 보통 한두 칸 안에서 끝난다. 끝까지 못 찾으면 남은 슬롯이
			// 전부 이 follower에게 중복이라는 뜻이고, 그때만 기배정 스왑으로 넘어간다.
			for (int j = cursor + 1; j < pool.length; j++) {
				if (acceptable(pool[j], follower)) {
					int tmp = pool[cursor];
					pool[cursor] = pool[j];
					pool[j] = tmp;
					return pool[cursor++];
				}
			}
			return swapWithAssigned(follower);
		}

		private boolean acceptable(int followee, int follower) {
			return followee != follower && takenMark[followee] != token;
		}

		/** 이미 배정을 마친 follower와 followee를 맞바꾼다. 교환이라 두 축의 합계가 보존된다. */
		private int swapWithAssigned(int follower) {
			int stuck = pool[cursor];
			for (int attempt = 0; attempt < SWAP_ATTEMPT_LIMIT && processedCount > 0; attempt++) {
				int other = processed[rnd.nextInt(processedCount)];
				if (other == stuck) {
					continue; // 자기 자신을 팔로우하게 된다
				}
				int from = offset[other];
				int to = offset[other + 1];
				if (from == to || contains(from, to, stuck)) {
					continue; // other가 이미 stuck을 팔로우한다
				}
				int at = from + rnd.nextInt(to - from);
				int taken = followees[at];
				if (!acceptable(taken, follower)) {
					continue;
				}
				followees[at] = stuck;
				swapFixups++;
				cursor++;
				return taken;
			}
			throw new IllegalStateException(
					"슬롯 스왑 보정이 수렴하지 않았다 (follower=" + follower + ", 남은 슬롯=" + (pool.length - cursor)
							+ "). 분포 명세가 실현 불가능한지 확인하라.");
		}

		private boolean contains(int from, int to, int value) {
			for (int i = from; i < to; i++) {
				if (followees[i] == value) {
					return true;
				}
			}
			return false;
		}

		private int[] ascendingIds() {
			int[] ids = new int[n];
			for (int i = 0; i < n; i++) {
				ids[i] = i + 1;
			}
			return ids;
		}

		private int[] shuffledIds() {
			int[] ids = ascendingIds();
			for (int i = n - 1; i > 0; i--) {
				int j = rnd.nextInt(i + 1);
				int tmp = ids[i];
				ids[i] = ids[j];
				ids[j] = tmp;
			}
			return ids;
		}
	}
}
