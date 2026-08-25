/**
 * 더미 데이터 생성기 (Phase 1 · P1-01~P1-05).
 *
 * <p>이 패키지는 {@code src/dummy/java} 소스셋에 있어 {@code bootJar} 산출물에 포함되지 않는다.
 * 백데이팅 팩토리 {@link com.timeline.common.snowflake.SnowflakeIdFactory}와 같은 격리 규칙을 따른다
 * (마스터 &sect;4.2 — 프로덕션에 백데이팅 경로가 노출되면 그것이 곧 ID 위조 경로다).
 *
 * <p><b>JPA를 쓰지 않는다.</b> 600만 행을 엔티티로 넣으면 며칠이 걸린다(마스터 &sect;8 Phase 1).
 * JDBC Batch Insert만 사용하며, {@code rewriteBatchedStatements=true}가 없으면
 * 드라이버가 multi-row INSERT로 재작성하지 않아 자릿수 단위로 느려진다(로드맵 &sect;4.7).
 *
 * <p>구성
 * <ul>
 *   <li>{@link com.timeline.datagen.SeedSpec} — 규모 명세(마스터 &sect;8 Phase 1 분포표 그대로)</li>
 *   <li>{@link com.timeline.datagen.DistributionPlanner} — 계층·코호트 배정과 슬롯 풀 알고리즘(순수 로직)</li>
 *   <li>{@link com.timeline.datagen.SeedMain} — 단계 실행 진입점</li>
 * </ul>
 */
package com.timeline.datagen;
