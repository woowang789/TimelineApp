/**
 * 타임라인 서비스 루트 패키지.
 *
 * <p>MSA를 하지 않는 대신 <strong>도메인별 패키지가 곧 모듈 경계</strong>다
 * (마스터 &sect;3 "의도적으로 제외: MSA", 로드맵 10-phase-0 4.1절).
 *
 * <p><strong>경계 규칙 2개</strong>
 * <ol>
 *   <li>도메인 패키지 간 참조는 <strong>Service 계층끼리만</strong> 한다.
 *       Repository·엔티티를 타 도메인이 직접 참조하지 않는다.</li>
 *   <li>{@code timeline}은 조회 전용 도메인으로 {@code post}·{@code follow}를 <strong>읽기만</strong> 한다.
 *       역방향 참조 금지 — Phase 2a에서 fan-out이 들어와도 이 경계가 유지되는지가
 *       "모듈 경계 명확한 모놀리식"의 검증 지점이다.</li>
 * </ol>
 */
package com.timeline;
