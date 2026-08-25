/**
 * 타임라인 도메인 — Phase 0에서는 경계만 잡는 빈 패키지다.
 *
 * <p>조회 전용 도메인 — {@code post}·{@code follow}를 읽기만 한다. 역방향 참조 금지.
 * Phase 1에서 Pull 조회, Phase 2a에서 Push 경로가 들어온다.
 *
 * <p>다른 도메인을 읽을 때도 경계 규칙 1을 따른다 — PostService·FollowService를 통하고
 * PostRepository·Follow 엔티티를 직접 건드리지 않는다.
 *
 * <p>{@code GET /timeline}은 Phase 0에서 구현하지 않는다. Pull 쿼리의 구현과 측정이 Phase 1의 본문이다.
 */
package com.timeline.timeline;
