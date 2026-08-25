package com.timeline.common.snowflake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snowflake ID 생성기 단위 테스트 (마스터 &sect;4.2).
 *
 * <p>Spring 컨텍스트가 필요 없다 — 생성기는 상태를 스스로 들고 있는 순수 객체다.
 * {@link SnowflakeIdFactory}는 {@code src/dummy/java} 소스셋에 있고, 그 출력물이 test 클래스패스에만
 * 올라가 있어 여기서만 보인다(main에서 참조하면 컴파일 에러).
 */
class SnowflakeIdTest {

	/** 같은 ms가 담을 수 있는 ID 개수 — 시퀀스 12bit. */
	private static final int IDS_PER_MILLI = (int) SnowflakeIdGenerator.MAX_SEQUENCE + 1;

	private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator();

	@Test
	@DisplayName("연속 호출한 ID는 단조 증가한다")
	void generatesMonotonicallyIncreasingIds() {
		long previous = generator.nextId();

		for (int i = 0; i < 5_000; i++) {
			long current = generator.nextId();
			assertThat(current).isGreaterThan(previous);
			previous = current;
		}
	}

	@Test
	@DisplayName("ms당 시퀀스 4,096개를 소진하면 다음 ms로 넘어가며, 중복 없이 계속 증가한다")
	void rollsOverToNextMilliWhenSequenceIsExhausted() {
		// 4,096 * 2 + 1개를 뽑으면 한 ms에 최대 4,096개라는 상한 때문에
		// 기계 속도와 무관하게 최소 3개의 서로 다른 ms가 반드시 나타난다.
		int count = IDS_PER_MILLI * 2 + 1;
		long[] ids = new long[count];
		Map<Long, Integer> countPerTimestamp = new HashMap<>();

		for (int i = 0; i < count; i++) {
			ids[i] = generator.nextId();
			countPerTimestamp.merge(timestampOf(ids[i]), 1, Integer::sum);
		}

		assertThat(ids).isSorted();
		assertThat(ids).doesNotHaveDuplicates();
		assertThat(countPerTimestamp).hasSizeGreaterThanOrEqualTo(3);
		assertThat(countPerTimestamp.values()).allSatisfy(
				perMilli -> assertThat(perMilli).isLessThanOrEqualTo(IDS_PER_MILLI));
	}

	@Test
	@DisplayName("ID에서 생성 시각과 노드ID를 복원할 수 있다")
	void restoresTimestampAndNodeIdFromId() {
		long before = System.currentTimeMillis();
		long id = generator.nextId();
		long after = System.currentTimeMillis();

		assertThat(timestampOf(id)).isBetween(before, after);
		// 프로덕션 노드ID는 0 고정(부록 B).
		assertThat(nodeIdOf(id)).isZero();
	}

	@Test
	@DisplayName("of()와 nextId()의 비트 배치가 같다 — 분해 후 재조립하면 원본과 일치한다")
	void factoryAndGeneratorShareTheSameBitLayout() {
		long id = generator.nextId();

		long rebuilt = SnowflakeIdFactory.of(timestampOf(id), (int) nodeIdOf(id), (int) sequenceOf(id));

		assertThat(rebuilt).isEqualTo(id);
	}

	@Test
	@DisplayName("of()는 비트 배치가 담지 못하는 인자를 거부한다")
	void factoryRejectsOutOfRangeArguments() {
		long epochMilli = SnowflakeIdGenerator.EPOCH_MILLI;

		assertThatThrownBy(() -> SnowflakeIdFactory.of(epochMilli - 1, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SnowflakeIdFactory.of(epochMilli, (int) SnowflakeIdGenerator.MAX_NODE_ID + 1, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SnowflakeIdFactory.of(epochMilli, 0, (int) SnowflakeIdGenerator.MAX_SEQUENCE + 1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static long timestampOf(long id) {
		return (id >>> SnowflakeIdGenerator.TIMESTAMP_SHIFT) + SnowflakeIdGenerator.EPOCH_MILLI;
	}

	private static long nodeIdOf(long id) {
		return (id >>> SnowflakeIdGenerator.NODE_ID_SHIFT) & SnowflakeIdGenerator.MAX_NODE_ID;
	}

	private static long sequenceOf(long id) {
		return id & SnowflakeIdGenerator.MAX_SEQUENCE;
	}
}
