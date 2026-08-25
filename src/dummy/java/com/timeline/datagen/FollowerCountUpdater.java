package com.timeline.datagen;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code users.follower_count} 일괄 집계 (P1-03의 뒷부분).
 *
 * <p><b>self-follow를 제외한다.</b> 스키마 주석(V1)이 규정한 대로 가입 시 들어가는 self 행은
 * 이 카운터에 세지 않는다 — 세면 모든 사용자의 팔로워 수가 1씩 부풀어 계층 분포 검증이 어긋난다.
 *
 * <p>애플리케이션이 원자적 UPDATE로 증감하는 값(카운터 SoT는 DB)을 생성기가 한 번에 채우는 것뿐이고,
 * 이후 팔로우/언팔로우 API가 이어서 증감한다.
 */
final class FollowerCountUpdater {

	private static final String SQL = """
			UPDATE users u
			LEFT JOIN (
			    SELECT followee_id, COUNT(*) AS c
			    FROM follows
			    WHERE follower_id <> followee_id
			    GROUP BY followee_id
			) t ON t.followee_id = u.id
			SET u.follower_count = IFNULL(t.c, 0)
			""";

	private FollowerCountUpdater() {
	}

	static void run(Db db) throws SQLException {
		Progress.log("follower_count 집계 UPDATE 시작 (self-follow 제외)");
		long start = System.nanoTime();
		try (Connection conn = db.open(); Statement st = conn.createStatement()) {
			int updated = st.executeUpdate(SQL);
			conn.commit();
			Progress.log("follower_count 집계 완료 — " + String.format("%,d", updated) + "행 · "
					+ (System.nanoTime() - start) / 1_000_000 + "ms");
		}
	}
}
