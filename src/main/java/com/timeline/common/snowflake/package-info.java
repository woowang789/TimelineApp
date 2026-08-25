/**
 * Snowflake ID 생성기 (마스터 &sect;4.2).
 *
 * <p>{@code SnowflakeIdGenerator} — 프로덕션 API는 {@code nextId()} 하나뿐이다.
 * 백데이팅 팩토리 {@code of(epochMilli, nodeId, sequence)}가 main에 있으면 그게 ID 위조 경로이므로,
 * 같은 패키지 경로의 {@code src/dummy/java} 소스셋에 {@code SnowflakeIdFactory}로 격리했다.
 * 비트 배치 상수가 package-private인 이유가 이것이다 — 소스셋이 달라도 같은 패키지면 접근된다.
 */
package com.timeline.common.snowflake;
