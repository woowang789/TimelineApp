/**
 * Snowflake ID 생성기 — Phase 0의 0.5에서 구현한다. 지금은 경계만 잡는 빈 패키지다.
 *
 * <p>들어올 것: SnowflakeIdGenerator — 프로덕션 API는 {@code nextId()} 하나뿐이다.
 * 백데이팅 팩토리 {@code of(epochMilli, nodeId, sequence)}가 main에 있으면 그게 ID 위조 경로이므로,
 * 같은 패키지 경로의 {@code src/dummy/java} 소스셋에 격리한다.
 */
package com.timeline.common.snowflake;
