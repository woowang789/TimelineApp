package com.timeline.common.snowflake;

/**
 * 타임스탬프 주입형 Snowflake ID 팩토리 — <b>더미 데이터 전용</b>(마스터 &sect;4.2).
 *
 * <p>Snowflake는 상위 41bit가 생성 시각이라 지금 300만 건을 만들면 전부 "오늘 몇 분 사이"의 ID가 된다.
 * {@code created_at}만 과거로 흩뿌리면 ID 순서와 시간 순서가 어긋나 "정렬 = 시간순 정렬"이라는
 * 전제가 더미에서 깨진다. Phase 1의 더미 데이터 생성기는 이 팩토리로 ID를 백데이팅해
 * ID 순서와 {@code created_at} 순서를 일치시킨다(노드ID 0~15 병렬 생성도 여기를 통한다).
 *
 * <p>이 클래스는 {@code src/dummy/java} 소스셋에 있어 main 클래스패스에 없다. main 코드가 호출하면
 * 컴파일 에러이고, {@code bootJar} 산출물에도 물리적으로 포함되지 않는다 — 백데이팅 API가
 * 프로덕션에 노출되면 그것이 곧 ID 위조 경로이기 때문이다. main과 같은 패키지에 두었기 때문에
 * {@link SnowflakeIdGenerator}의 package-private 비트 배치 상수를 그대로 재사용한다
 * (같은 상수를 쓰므로 두 생성 경로의 비트 배치가 갈라질 수 없다).
 */
public final class SnowflakeIdFactory {

	private SnowflakeIdFactory() {
	}

	/**
	 * 주어진 시각·노드·시퀀스로 ID를 조립한다.
	 *
	 * @param epochMilli 생성 시각 (Unix epoch ms). 커스텀 epoch 2025-01-01T00:00:00Z 이후여야 한다
	 * @param nodeId     노드 ID (0~1023)
	 * @param sequence   같은 ms 안의 일련번호 (0~4095)
	 * @return 조립된 64bit Snowflake ID
	 * @throws IllegalArgumentException 인자가 비트 배치가 담을 수 있는 범위를 벗어난 경우
	 */
	public static long of(long epochMilli, int nodeId, int sequence) {
		long elapsed = epochMilli - SnowflakeIdGenerator.EPOCH_MILLI;
		if (elapsed < 0) {
			throw new IllegalArgumentException(
					"커스텀 epoch(2025-01-01T00:00:00Z) 이전 시각은 표현할 수 없다: epochMilli=" + epochMilli);
		}
		if (nodeId < 0 || nodeId > SnowflakeIdGenerator.MAX_NODE_ID) {
			throw new IllegalArgumentException(
					"nodeId는 0~" + SnowflakeIdGenerator.MAX_NODE_ID + " 범위여야 한다: " + nodeId);
		}
		if (sequence < 0 || sequence > SnowflakeIdGenerator.MAX_SEQUENCE) {
			throw new IllegalArgumentException(
					"sequence는 0~" + SnowflakeIdGenerator.MAX_SEQUENCE + " 범위여야 한다: " + sequence);
		}

		return (elapsed << SnowflakeIdGenerator.TIMESTAMP_SHIFT)
				| ((long) nodeId << SnowflakeIdGenerator.NODE_ID_SHIFT)
				| sequence;
	}
}
