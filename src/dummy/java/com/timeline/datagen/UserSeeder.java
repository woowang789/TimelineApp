package com.timeline.datagen;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * users 적재 (P1-01).
 *
 * <p><b>BCrypt 해시는 딱 한 번만 계산해 전원이 공유한다.</b> 10만 명을 각자 해싱하면
 * 강도 10 기준 대략 100ms &times; 100,000 = 3시간이 그냥 사라진다. 게다가 k6 {@code setup()}이
 * 코호트 5,000명에게 같은 평문으로 로그인해야 하므로(마스터 &sect;4.2) 비밀번호는 어차피 전원 동일하다.
 * "같은 평문 → 같은 해시"는 BCrypt의 salt 때문에 성립하지 않지만, 반대로 <b>같은 해시를 복사해 두면</b>
 * 그 해시가 검증하는 평문도 하나뿐이라 로그인은 그대로 성립한다.
 */
final class UserSeeder {

	private static final String SQL = """
			INSERT INTO users (id, username, password, nickname, follower_count, created_at)
			VALUES (?, ?, ?, ?, 0, ?)
			""";

	private static final String NICK_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

	private UserSeeder() {
	}

	static void run(Db db, SeedSpec spec, long nowMillis) throws SQLException {
		db.requireEmpty("users");

		long hashStart = System.nanoTime();
		String sharedHash = new BCryptPasswordEncoder().encode(SeedMain.SEED_PASSWORD);
		Progress.log("공유 BCrypt 해시 1회 계산 완료 (" + (System.nanoTime() - hashStart) / 1_000_000 + "ms) — "
				+ "평문 '" + SeedMain.SEED_PASSWORD + "'는 k6 setup()과 공유한다");

		Random rnd = new Random(spec.randomSeed());
		long from = SeedTime.daysBefore(nowMillis, SeedTime.USER_FROM_DAYS);
		long to = SeedTime.daysBefore(nowMillis, SeedTime.USER_TO_DAYS);

		Progress progress = new Progress("users", spec.users());
		try (Connection conn = db.open();
				BatchWriter writer = new BatchWriter(conn, SQL, progress)) {
			PreparedStatement ps = writer.statement();
			for (int id = 1; id <= spec.users(); id++) {
				ps.setInt(1, id);
				ps.setString(2, String.format("user_%06d", id));
				ps.setString(3, sharedHash);
				ps.setString(4, nickname(rnd));
				ps.setString(5, SeedTime.literal(SeedTime.between(rnd, from, to)));
				writer.addRow();
			}
			writer.flush();
		}
		progress.finish();
	}

	private static String nickname(Random rnd) {
		char[] buf = new char[6];
		for (int i = 0; i < buf.length; i++) {
			buf[i] = NICK_ALPHABET.charAt(rnd.nextInt(NICK_ALPHABET.length()));
		}
		return "nick_" + new String(buf);
	}
}
