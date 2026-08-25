package com.timeline.datagen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 측정 코호트 export — {@code k6/data/cohorts.json} (P1-05 · 마스터 &sect;4.2).
 *
 * <p>k6 {@code setup()}이 이 파일을 읽어 <b>시나리오가 쓰는 코호트 분량만</b> 로그인해 JWT를 사전 발급하고,
 * VU들이 그 토큰을 재사용한다. 매 요청 로그인하면 BCrypt가 병목이 되어 타임라인이 아니라
 * 인증 비용을 재게 된다(마스터 &sect;9.3).
 *
 * <p>스펙에서 계산한 id 구간을 그대로 쓰지 않고 <b>DB에서 다시 읽는다.</b> 파일과 DB가 어긋나면
 * k6가 없는 사용자로 로그인을 시도하고, 그 실패는 측정 중에야 드러난다.
 */
final class CohortExporter {

	private static final Path OUTPUT = Path.of("k6", "data", "cohorts.json");

	private static final String SQL = "SELECT id, username FROM users WHERE id BETWEEN ? AND ? ORDER BY id";

	private CohortExporter() {
	}

	static void run(Db db, SeedSpec spec) throws SQLException {
		StringBuilder json = new StringBuilder("{\n");
		int start = 1;

		try (Connection conn = db.open(); PreparedStatement ps = conn.prepareStatement(SQL)) {
			var cohorts = spec.measurementCohorts();
			for (int i = 0; i < cohorts.size(); i++) {
				SeedSpec.Group cohort = cohorts.get(i);
				int end = start + cohort.members() - 1;

				json.append("  \"").append(cohort.name()).append("\": [\n");
				int rows = appendMembers(ps, json, start, end);
				json.append("\n  ]").append(i < cohorts.size() - 1 ? "," : "").append('\n');

				if (rows != cohort.members()) {
					throw new IllegalStateException("코호트 " + cohort.name() + " 인원이 DB와 다르다: 기대 "
							+ cohort.members() + " / 실제 " + rows + " (users 적재를 먼저 끝냈는가?)");
				}
				Progress.log("  코호트 " + cohort.name() + " — id " + start + "~" + end
						+ " (" + rows + "명 · 팔로잉 " + cohort.perUser() + ")");
				start = end + 1;
			}
		}
		json.append("}\n");

		try {
			Files.createDirectories(OUTPUT.getParent());
			Files.writeString(OUTPUT, json.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("cohorts.json 기록 실패: " + OUTPUT.toAbsolutePath(), e);
		}
		Progress.log("코호트 export 완료 — " + OUTPUT.toAbsolutePath());
	}

	private static int appendMembers(PreparedStatement ps, StringBuilder json, int start, int end)
			throws SQLException {
		ps.setInt(1, start);
		ps.setInt(2, end);
		int rows = 0;
		try (ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				if (rows++ > 0) {
					json.append(",\n");
				}
				json.append("    {\"id\": ").append(rs.getLong(1))
						.append(", \"username\": \"").append(rs.getString(2)).append("\"}");
			}
		}
		return rows;
	}
}
