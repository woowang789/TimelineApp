package com.timeline.datagen;

import java.sql.SQLException;

/**
 * 더미 데이터 생성기 진입점 (P1-01~P1-05).
 *
 * <pre>
 * ./gradlew seed                                  # 전체 (풀 스케일)
 * ./gradlew seed --args="all --scale=smoke"       # 축소 스케일 스모크
 * ./gradlew seed --args="posts"                   # 단계 하나만
 * </pre>
 *
 * <p><b>단계 순서는 users → follows → posts → counts → cohorts로 고정이다.</b>
 * follows가 users FK를, posts가 author FK를 참조하므로 순서를 바꾸면 적재가 실패한다(로드맵 &sect;4.1).
 * counts는 follows 뒤라면 언제든 되지만, 재실행 시 순서를 외우지 않아도 되게 한 줄로 고정했다.
 */
public final class SeedMain {

	/**
	 * 더미 사용자 전원의 비밀번호 평문. <b>k6 {@code setup()}과 공유한다</b>(마스터 &sect;4.2) —
	 * 이 값이 바뀌면 부하 스크립트의 로그인이 전부 401이 된다.
	 */
	public static final String SEED_PASSWORD = "password123";

	private static final String USAGE = """
			사용법: seed [단계] [옵션]
			  단계    all(기본) | users | follows | posts | counts | cohorts
			  옵션    --scale=full(기본)|smoke
			          --url=<jdbc-url> --user=<계정> --password=<비밀번호>
			""";

	private SeedMain() {
	}

	public static void main(String[] args) throws SQLException {
		String stage = "all";
		String scale = "full";
		String url = Db.DEFAULT_URL;
		String user = Db.DEFAULT_USER;
		String password = Db.DEFAULT_PASSWORD;

		for (String arg : args) {
			if (arg.startsWith("--scale=")) {
				scale = value(arg);
			} else if (arg.startsWith("--url=")) {
				url = value(arg);
			} else if (arg.startsWith("--user=")) {
				user = value(arg);
			} else if (arg.startsWith("--password=")) {
				password = value(arg);
			} else if (arg.startsWith("--")) {
				throw new IllegalArgumentException("알 수 없는 옵션: " + arg + "\n" + USAGE);
			} else {
				stage = arg;
			}
		}

		SeedSpec spec = SeedSpec.of(scale);
		Db db = new Db(url, user, password);
		long now = System.currentTimeMillis();
		long start = System.nanoTime();

		Progress.log("=== 더미 데이터 시드 — 단계 '" + stage + "' · 스케일 '" + spec.name() + "' ===");
		Progress.log(String.format("사용자 %,d / 실팔로우 %,d(+self %,d) / 게시글 %,d · RNG 시드 %d",
				spec.users(), spec.followTotal(), spec.users(), spec.posts(), spec.randomSeed()));
		Progress.log("접속 " + db.url());

		switch (stage) {
			case "all" -> {
				UserSeeder.run(db, spec, now);
				FollowSeeder.run(db, spec, buildPlan(spec), now);
				PostSeeder.run(db, spec, now);
				FollowerCountUpdater.run(db);
				CohortExporter.run(db, spec);
			}
			case "users" -> UserSeeder.run(db, spec, now);
			case "follows" -> FollowSeeder.run(db, spec, buildPlan(spec), now);
			case "posts" -> PostSeeder.run(db, spec, now);
			case "counts" -> FollowerCountUpdater.run(db);
			case "cohorts" -> CohortExporter.run(db, spec);
			default -> throw new IllegalArgumentException("알 수 없는 단계: " + stage + "\n" + USAGE);
		}

		Progress.log(String.format("=== 완료 — 총 %.1fs ===", (System.nanoTime() - start) / 1e9));
	}

	private static FollowPlan buildPlan(SeedSpec spec) {
		Progress.log("계층·코호트 배정 시작 (슬롯 풀 " + String.format("%,d", spec.followTotal()) + "개)");
		long start = System.nanoTime();
		FollowPlan plan = DistributionPlanner.plan(spec);
		Progress.log("배정 완료 — " + (System.nanoTime() - start) / 1_000_000 + "ms · 스왑 보정 "
				+ plan.swapFixups() + "회");
		return plan;
	}

	private static String value(String arg) {
		return arg.substring(arg.indexOf('=') + 1);
	}
}
