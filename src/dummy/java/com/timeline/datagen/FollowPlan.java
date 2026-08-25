package com.timeline.datagen;

/**
 * 팔로우 관계 배정 결과 — {@link DistributionPlanner}의 산출물.
 *
 * <p>쌍을 {@code (follower, followee)} 튜플 배열로 들고 있지 않다. followee만 한 줄로 늘어놓고
 * follower별 구간을 {@code offset}으로 가리킨다. 이유는 두 가지다.
 * <ul>
 *   <li>메모리 — 300만 쌍에서 follower 배열 12MB가 통째로 빠진다</li>
 *   <li>적재 속도 — 구간이 follower id 오름차순이고 구간 안도 followee 오름차순이라,
 *       그대로 훑으면 INSERT가 {@code UNIQUE (follower_id, followee_id)} 인덱스에 순차로 들어간다.
 *       무작위 순서로 넣으면 이 보조 인덱스가 매 행마다 다른 페이지를 건드린다</li>
 * </ul>
 */
public final class FollowPlan {

	private final int users;
	/** 크기 users+2. 사용자 u의 followee들은 followees[offset[u] .. offset[u+1]) 에 있다 (u는 1-based). */
	private final int[] offset;
	private final int[] followees;
	/** 크기 users+1. 계층 배정으로 정해진 목표 팔로워 수 — 슬롯 풀이 모두 소비되므로 실측과 정확히 같다. */
	private final int[] followerTargets;
	/** 크기 users+1. 계층 이름 인덱스 (SeedSpec.tiers의 순서). */
	private final byte[] tierIndex;
	private final String[] tierNames;
	private final long swapFixups;

	FollowPlan(int users, int[] offset, int[] followees, int[] followerTargets,
			byte[] tierIndex, String[] tierNames, long swapFixups) {
		this.users = users;
		this.offset = offset;
		this.followees = followees;
		this.followerTargets = followerTargets;
		this.tierIndex = tierIndex;
		this.tierNames = tierNames;
		this.swapFixups = swapFixups;
	}

	public int users() {
		return users;
	}

	/** 실팔로우 관계 총수. self-follow는 포함하지 않는다 — 적재 단계가 따로 넣는다. */
	public int totalPairs() {
		return followees.length;
	}

	/** 사용자 {@code userId}의 팔로잉 수 (코호트 쿼터). */
	public int followingCount(int userId) {
		return offset[userId + 1] - offset[userId];
	}

	/** 사용자 {@code userId}가 팔로우하는 {@code k}번째 상대. {@code k}는 0 이상 {@link #followingCount}: 미만. */
	public int followeeAt(int userId, int k) {
		return followees[offset[userId] + k];
	}

	/** 사용자 {@code userId}의 목표 팔로워 수 (계층 배정 결과). */
	public int followerTarget(int userId) {
		return followerTargets[userId];
	}

	/** 사용자 {@code userId}가 배정된 계층 이름 (S/A/B/C). */
	public String tierOf(int userId) {
		return tierNames[tierIndex[userId]];
	}

	/** 슬롯 스왑 보정이 일어난 횟수. 값 자체는 리포트용 관측치다 — 0이 아니어도 분포는 정확하다. */
	public long swapFixups() {
		return swapFixups;
	}
}
