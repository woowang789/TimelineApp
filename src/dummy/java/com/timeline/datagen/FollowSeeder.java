package com.timeline.datagen;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;

/**
 * follows 적재 (P1-03).
 *
 * <p>실팔로우 관계에 더해 <b>전 사용자의 self-follow 행</b>을 넣는다(마스터 &sect;4.3 불변식).
 * 가입 API가 하는 일을 생성기도 똑같이 해야 한다 — 이 행이 없으면 Pull 타임라인 쿼리
 * (posts JOIN follows)가 <b>내 글을 빼고</b> 돌려주고, 그러면 Phase 2의 Push 결과 집합과
 * 달라져 두 방식의 p99 비교 자체가 성립하지 않는다.
 *
 * <p>self 행을 따로 몰아 넣지 않고 follower마다 자기 실관계 앞에 끼워 넣는다.
 * 두 번 훑으면 {@code UNIQUE (follower_id, followee_id)} 인덱스의 같은 페이지를 두 번 방문한다.
 */
final class FollowSeeder {

	private static final String SQL = """
			INSERT INTO follows (follower_id, followee_id, created_at)
			VALUES (?, ?, ?)
			""";

	private FollowSeeder() {
	}

	static void run(Db db, SeedSpec spec, FollowPlan plan, long nowMillis) throws SQLException {
		db.requireEmpty("follows");

		Random rnd = new Random(spec.randomSeed() + 1);
		long from = SeedTime.daysBefore(nowMillis, SeedTime.FOLLOW_FROM_DAYS);
		long to = SeedTime.daysBefore(nowMillis, SeedTime.FOLLOW_TO_DAYS);

		long total = (long) plan.totalPairs() + plan.users();
		Progress progress = new Progress("follows", total);
		try (Connection conn = db.open();
				BatchWriter writer = new BatchWriter(conn, SQL, progress)) {
			PreparedStatement ps = writer.statement();
			for (int follower = 1; follower <= plan.users(); follower++) {
				write(writer, ps, follower, follower, rnd, from, to);
				int following = plan.followingCount(follower);
				for (int k = 0; k < following; k++) {
					write(writer, ps, follower, plan.followeeAt(follower, k), rnd, from, to);
				}
			}
			writer.flush();
		}
		progress.finish();
		Progress.log("  실관계 " + String.format("%,d", plan.totalPairs())
				+ " + self-follow " + String.format("%,d", plan.users())
				+ " · 슬롯 스왑 보정 " + plan.swapFixups() + "회");
	}

	private static void write(BatchWriter writer, PreparedStatement ps, int follower, int followee,
			Random rnd, long from, long to) throws SQLException {
		ps.setInt(1, follower);
		ps.setInt(2, followee);
		ps.setString(3, SeedTime.literal(SeedTime.between(rnd, from, to)));
		writer.addRow();
	}
}
