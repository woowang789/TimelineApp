package com.timeline.common.snowflake;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Snowflake ID 생성기 (마스터 &sect;4.2).
 *
 * <pre>
 * 64bit = [1bit 미사용][41bit 타임스탬프][10bit 노드ID][12bit 시퀀스]
 * </pre>
 *
 * <p>상위 비트가 시각이므로 <b>ID 정렬 = 시간순 정렬</b>이다. 이 전제 위에 Redis Sorted Set의 score와
 * 커서 페이지네이션의 커서가 올라간다. 따라서 <b>프로덕션 API는 {@link #nextId()} 하나뿐이다</b> —
 * 타임스탬프 주입형 백데이팅 팩토리를 여기에 두면 그것이 곧 ID 위조 경로가 된다(&sect;4.2).
 * 더미 데이터용 {@code of(epochMilli, nodeId, sequence)}는 {@code src/dummy/java}의 같은 패키지에
 * {@code SnowflakeIdFactory}로 격리했고, 그래서 아래 비트 배치 상수들이 package-private이다.
 */
@Component
public class SnowflakeIdGenerator {

	/**
	 * 커스텀 epoch — 2025-01-01T00:00:00Z. 마스터 부록 B에 등재된 확정 결정으로, 한 번 정하면 불변이다.
	 * (이미 발급된 ID 전부의 시각 해석이 여기에 걸려 있다.)
	 */
	static final long EPOCH_MILLI = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();

	static final int SEQUENCE_BITS = 12;
	static final int NODE_ID_BITS = 10;

	static final int NODE_ID_SHIFT = SEQUENCE_BITS;
	static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;

	static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
	static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;

	/** 프로덕션 노드ID — 단일 인스턴스 운영이므로 0 고정(부록 B). 노드ID 0~15 병렬 생성은 더미 생성기의 몫이다. */
	private static final long NODE_ID = 0L;

	/**
	 * 시계 역행 허용 폭. 이보다 작은 역행은 대기로 흡수하고, 크면 즉시 실패한다.
	 *
	 * <p>근거: 단일 인스턴스 로컬 실행이 전제이므로 노드 간 ID 충돌은 애초에 없고, 남는 위험은
	 * "같은 노드에서 ID가 역전되는 것" 하나다. NTP slew처럼 수 ms 단위로 조금씩 되감기는 경우는
	 * 대기하면 그대로 흡수된다. 반면 컨테이너 VM이 절전에서 깨어나며 시각을 크게 되돌리는 step 보정은
	 * 대기로 버티면 스레드가 락을 쥔 채 수 분간 멈춰 서므로, 조용히 멎는 대신 시끄럽게 실패시킨다.
	 * (lastTimestamp를 그대로 재사용해 계속 발급하는 선택지도 있으나, 실제 시각보다 앞선 ID를 남겨
	 * "ID = 시각"이라는 전제를 소리 없이 깨므로 택하지 않았다.)
	 */
	private static final long CLOCK_BACKWARD_TOLERANCE_MILLIS = 5L;

	private long lastTimestamp = -1L;
	private long sequence = 0L;

	/**
	 * 단조 증가하는 64bit ID를 발급한다.
	 *
	 * <p>{@code synchronized}로 충분하다 — 이 프로젝트의 쓰기 비중은 2%(읽기:쓰기 50:1)이고
	 * ms당 4,096개가 상한이라 락 경합이 병목이 될 지점이 아니다. CAS 기반 최적화는 측정으로
	 * 필요가 드러나기 전에는 하지 않는다.
	 *
	 * @throws IllegalStateException 시계가 허용 폭을 넘어 역행해 단조 증가를 보장할 수 없을 때
	 */
	public synchronized long nextId() {
		long timestamp = System.currentTimeMillis();

		if (timestamp < lastTimestamp) {
			timestamp = handleClockBackwards(timestamp);
		}

		if (timestamp == lastTimestamp) {
			sequence = (sequence + 1) & MAX_SEQUENCE;
			if (sequence == 0) {
				// 이 ms의 시퀀스 4,096개를 다 썼다 — 다음 ms까지 기다린다.
				timestamp = spinUntil(lastTimestamp + 1);
			}
		} else {
			sequence = 0L;
		}

		lastTimestamp = timestamp;

		return ((timestamp - EPOCH_MILLI) << TIMESTAMP_SHIFT)
				| (NODE_ID << NODE_ID_SHIFT)
				| sequence;
	}

	private long handleClockBackwards(long currentTimestamp) {
		long drift = lastTimestamp - currentTimestamp;
		if (drift > CLOCK_BACKWARD_TOLERANCE_MILLIS) {
			throw new IllegalStateException(
					"시계가 " + drift + "ms 역행해 ID 단조 증가를 보장할 수 없다. 발급을 중단한다.");
		}
		return spinUntil(lastTimestamp);
	}

	/** {@code target} 이상이 될 때까지 대기한다. 대기 상한이 짧아(≤ 5ms) sleep보다 스핀이 싸다. */
	private long spinUntil(long target) {
		long timestamp = System.currentTimeMillis();
		while (timestamp < target) {
			timestamp = System.currentTimeMillis();
		}
		return timestamp;
	}
}
