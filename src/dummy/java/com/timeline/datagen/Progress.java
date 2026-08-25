package com.timeline.datagen;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 단계별 진행률·소요 시간 로그 (로드맵 &sect;6 "적재 시간 폭주" 리스크 대응).
 *
 * <p>600만 행 적재는 수십 분이 걸린다. 완료 예상 시각을 볼 수 없으면 "느린 것"과 "멈춘 것"을
 * 구분할 수 없어서, 로드맵이 요구하는 "진행률 로그 기준 완료 예상 시각 초과" 판단 자체가 불가능하다.
 *
 * <p>여러 스레드가 함께 쓴다(posts 16스레드). 카운터만 원자적이면 되고 로그 줄이 아주 가끔
 * 순서가 뒤바뀌는 건 상관없다 — 락을 잡을 이유가 없다.
 */
final class Progress {

	/** 로그 간격. 로드맵 P1-01의 "만 행 단위". */
	private static final long LOG_ROWS = 10_000;

	private final String stage;
	private final long total;
	private final long startNanos = System.nanoTime();
	private final AtomicLong done = new AtomicLong();
	private final AtomicLong nextLog = new AtomicLong(LOG_ROWS);

	Progress(String stage, long total) {
		this.stage = stage;
		this.total = total;
		log(stage + " 시작 — 목표 " + format(total) + "행");
	}

	void advance(long rows) {
		long now = done.addAndGet(rows);
		long threshold = nextLog.get();
		if (now >= threshold && nextLog.compareAndSet(threshold, threshold + LOG_ROWS)) {
			report(now);
		}
	}

	void finish() {
		report(done.get());
		log(stage + " 완료 — " + format(done.get()) + "행 · " + elapsedText());
	}

	long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private void report(long now) {
		long elapsed = Math.max(1, elapsedMillis());
		long rowsPerSec = now * 1000 / elapsed;
		String eta = rowsPerSec > 0 && total > now
				? " · 잔여 ~" + duration((total - now) * 1000 / rowsPerSec)
				: "";
		log(String.format("[%s] %s / %s (%.1f%%) · %s · %s행/s%s",
				stage, format(now), format(total), total == 0 ? 100.0 : 100.0 * now / total,
				elapsedText(), format(rowsPerSec), eta));
	}

	private String elapsedText() {
		return duration(elapsedMillis());
	}

	private static String duration(long millis) {
		Duration d = Duration.ofMillis(millis);
		if (d.toMinutes() < 1) {
			return String.format("%.1fs", millis / 1000.0);
		}
		return String.format("%dm %02ds", d.toMinutes(), d.toSecondsPart());
	}

	private static String format(long value) {
		return String.format("%,d", value);
	}

	/** 단계 바깥에서도 쓰는 공용 로그. 타임스탬프를 붙여 야간 실행 로그에서 구간을 되짚을 수 있게 한다. */
	static void log(String message) {
		System.out.printf("%tT  %s%n", System.currentTimeMillis(), message);
	}
}
