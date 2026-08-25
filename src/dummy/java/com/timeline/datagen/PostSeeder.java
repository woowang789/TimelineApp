package com.timeline.datagen;

import com.timeline.common.snowflake.SnowflakeIdFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * posts 적재 — 16스레드 백데이팅 (P1-04).
 *
 * <h2>왜 스레드마다 노드ID를 고정 소유하는가</h2>
 * Snowflake ID는 {@code [타임스탬프][노드ID][시퀀스]}다. 스레드가 노드ID를 공유하면 같은 ms에
 * 같은 시퀀스를 발급해 ID가 충돌하고, 그걸 막으려면 스레드 간 동기화가 들어가 병렬화가 무의미해진다.
 * <b>스레드 t가 노드ID t를 독점</b>하면 노드ID 비트가 달라 다른 스레드와는 충돌이 원천적으로 불가능하고,
 * 같은 스레드 안에서는 시퀀스를 혼자 관리하므로 락이 필요 없다(로드맵 &sect;4.7).
 *
 * <h2>왜 스레드 안에서 시각을 정렬하는가</h2>
 * 두 가지를 한 번에 얻는다.
 * <ul>
 *   <li><b>ms당 4,096개 한계 처리</b> — 오름차순으로 훑으면 "같은 ms가 연속으로 나오는 구간"에서만
 *       시퀀스를 올리면 되고, 4,096을 넘기면 다음 ms로 넘긴다. 해시맵으로 ms별 시퀀스를 들고 있을 이유가 없다</li>
 *   <li><b>PK 삽입 지역성</b> — 백데이팅 ID는 180일치 id 공간에 흩어진다. 무작위 순서로 넣으면
 *       매 행이 클러스터드 인덱스의 다른 페이지를 건드린다. 노드ID 비트가 타임스탬프 비트보다 <i>아래</i>라
 *       16스레드가 비슷한 시각대를 나란히 올라가면 전체 삽입이 거의 순차가 된다</li>
 * </ul>
 *
 * <p>{@code created_at}은 ID에 주입한 epochMilli와 <b>같은 값</b>을 쓴다. "ID 순서 = 시간 순서"라는
 * 전제를 더미에서도 유지해야 커서 페이지네이션과 Redis score가 같은 의미를 갖는다(마스터 &sect;4.2).
 */
final class PostSeeder {

	/** 스레드 = 노드ID 0~15. */
	private static final int THREADS = 16;

	/** SnowflakeIdGenerator의 12bit 시퀀스 상한. 상수가 package-private이라 값을 옮겨 적는다. */
	private static final int MAX_SEQUENCE = 4095;

	/** 본문 길이 [40, 120] 균등 → 평균 80B (마스터 &sect;8 Phase 1). ASCII라 문자 수 = LENGTH() 바이트 수다. */
	private static final int MIN_CONTENT = 40;
	private static final int MAX_CONTENT = 120;
	/** 공백을 넣지 않는다 — 끝에 붙은 공백이 있으면 "평균 80B"를 눈으로 확인하기 어려워진다. */
	private static final String ALPHABET =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	private static final String SQL = """
			INSERT INTO posts (id, author_id, content, like_count, is_deleted, created_at)
			VALUES (?, ?, ?, 0, FALSE, ?)
			""";

	private PostSeeder() {
	}

	static void run(Db db, SeedSpec spec, long nowMillis) throws SQLException {
		db.requireEmpty("posts");

		Progress progress = new Progress("posts", spec.posts());
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		List<Future<?>> futures = new ArrayList<>(THREADS);
		try {
			for (int node = 0; node < THREADS; node++) {
				int nodeId = node;
				int count = (int) (spec.posts() / THREADS + (node < spec.posts() % THREADS ? 1 : 0));
				futures.add(pool.submit(() -> {
					seedNode(db, spec, nowMillis, nodeId, count, progress);
					return null;
				}));
			}
			for (Future<?> future : futures) {
				await(future);
			}
		} finally {
			pool.shutdownNow();
		}
		progress.finish();
	}

	private static void await(Future<?> future) throws SQLException {
		try {
			future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("posts 적재가 중단됐다", e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof SQLException sqlException) {
				throw sqlException;
			}
			throw new IllegalStateException("posts 적재 스레드가 실패했다", e.getCause());
		}
	}

	private static void seedNode(Db db, SeedSpec spec, long nowMillis, int nodeId, int count,
			Progress progress) throws SQLException {
		// 스레드마다 다른 시드를 쓰되 노드ID로 결정한다 — 스레드 스케줄링과 무관하게 재현된다.
		Random rnd = new Random(spec.randomSeed() + 1_000L + nodeId);
		long[] stamps = timestamps(rnd, nowMillis, count);
		Arrays.sort(stamps);

		try (Connection conn = db.open();
				BatchWriter writer = new BatchWriter(conn, SQL, progress)) {
			PreparedStatement ps = writer.statement();
			char[] buffer = new char[MAX_CONTENT];
			long lastMillis = -1;
			int sequence = 0;

			for (long raw : stamps) {
				long millis = raw;
				if (millis <= lastMillis) {
					millis = lastMillis;
					if (++sequence > MAX_SEQUENCE) {
						millis = lastMillis + 1;
						sequence = 0;
					}
				} else {
					sequence = 0;
				}
				lastMillis = millis;

				ps.setLong(1, SnowflakeIdFactory.of(millis, nodeId, sequence));
				ps.setInt(2, 1 + rnd.nextInt(spec.users()));
				ps.setString(3, content(rnd, buffer));
				ps.setString(4, SeedTime.literal(millis));
				writer.addRow();
			}
			writer.flush();
		}
	}

	/** 시간 분포: 최근 7일 25% / 8~30일 35% / 31~180일 40%. 버킷 안은 균등이다. */
	private static long[] timestamps(Random rnd, long nowMillis, int count) {
		long recentFrom = SeedTime.daysBefore(nowMillis, SeedTime.POST_RECENT_DAYS);
		long midFrom = SeedTime.daysBefore(nowMillis, SeedTime.POST_MID_DAYS);
		long oldFrom = SeedTime.daysBefore(nowMillis, SeedTime.POST_WINDOW_DAYS);

		int recent = (int) Math.round(count * SeedTime.POST_RECENT_RATIO);
		int mid = (int) Math.round(count * SeedTime.POST_MID_RATIO);
		int old = count - recent - mid;

		long[] stamps = new long[count];
		int at = 0;
		for (int i = 0; i < recent; i++) {
			stamps[at++] = SeedTime.between(rnd, recentFrom, nowMillis);
		}
		for (int i = 0; i < mid; i++) {
			stamps[at++] = SeedTime.between(rnd, midFrom, recentFrom);
		}
		for (int i = 0; i < old; i++) {
			stamps[at++] = SeedTime.between(rnd, oldFrom, midFrom);
		}
		return stamps;
	}

	private static String content(Random rnd, char[] buffer) {
		int length = MIN_CONTENT + rnd.nextInt(MAX_CONTENT - MIN_CONTENT + 1);
		for (int i = 0; i < length; i++) {
			buffer[i] = ALPHABET.charAt(rnd.nextInt(ALPHABET.length()));
		}
		return new String(buffer, 0, length);
	}
}
