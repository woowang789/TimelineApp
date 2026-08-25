package com.timeline.datagen;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * 시간 창 정의와 {@code DATETIME(6)} 리터럴 변환.
 *
 * <p><b>시간대를 드라이버에 맡기지 않는다.</b> epoch ms를 UTC 벽시계 문자열로 직접 만들어
 * {@code setString}으로 넣는다. {@code Timestamp}로 넘기면 JVM 기본 시간대 → 세션 시간대 변환이
 * 끼어들고, 그러면 "게시글 id의 상위 41bit == created_at"이라는 검증(로드맵 P1-04)이
 * 서울/UTC 9시간 차이로 통째로 어긋난다. 검증 스크립트도 같은 이유로 세션 시간대를 UTC로 고정한다.
 *
 * <p>세 테이블의 시간 창은 <b>users &lt; follows &lt; posts</b> 순으로 겹치지 않게 잡았다.
 * 측정에 쓰이지는 않지만(로드맵 &sect;4.7), "가입보다 먼저 쓴 글"이 있으면 데이터를 들여다보는
 * 사람이 생성기를 의심하게 된다.
 */
final class SeedTime {

	private SeedTime() {
	}

	static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

	/** 게시글 시간 창 — 최근 180일 (마스터 &sect;8 Phase 1). */
	static final long POST_WINDOW_DAYS = 180;
	/** 게시글 시간 분포 경계와 비율. 버킷 안은 균등이다. */
	static final long POST_RECENT_DAYS = 7;
	static final long POST_MID_DAYS = 30;
	static final double POST_RECENT_RATIO = 0.25;
	static final double POST_MID_RATIO = 0.35;

	/** 팔로우 시간 창 — 게시글보다 이전(180~270일 전). */
	static final long FOLLOW_FROM_DAYS = 270;
	static final long FOLLOW_TO_DAYS = 180;

	/** 가입 시간 창 — 팔로우보다 이전(270~635일 전). */
	static final long USER_FROM_DAYS = 635;
	static final long USER_TO_DAYS = 270;

	private static final DateTimeFormatter LITERAL =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

	/** epoch ms → MySQL DATETIME(6) 리터럴 (UTC 벽시계, 밀리초까지). */
	static String literal(long epochMilli) {
		return LITERAL.format(Instant.ofEpochMilli(epochMilli));
	}

	/** {@code [from, to)} 구간의 균등 난수. {@code nextDouble()}만 써서 JDK 버전과 무관하게 재현된다. */
	static long between(Random rnd, long from, long to) {
		return from + (long) (rnd.nextDouble() * (to - from));
	}

	static long daysBefore(long nowMillis, long days) {
		return nowMillis - days * DAY_MILLIS;
	}
}
