/**
 * 타임라인 도메인 — Phase 1에서 Pull 조회가 들어왔고, Phase 2a에서 Push 경로가 그 앞에 붙는다.
 * Push가 생겨도 <strong>Pull은 폴백으로 남는다</strong>(마스터 &sect;5-7).
 *
 * <p>조회 전용 도메인 — {@code post}·{@code follow}를 읽기만 한다. 역방향 참조 금지.
 *
 * <p><strong>경계 규칙 1의 읽기 전용 특례가 적용되는 유일한 패키지다.</strong>
 * 다른 도메인이라면 타 도메인 데이터를 Service를 통해 읽지만, 여기서는 그럴 수 없다 —
 * 마스터 &sect;8 Phase 1이 규정한 Pull 쿼리가 {@code posts JOIN follows} <em>한 문장</em>이고,
 * 그 문장이 곧 이 프로젝트의 측정 대상이기 때문이다. FollowService로 팔로잉 목록을 받고
 * PostService로 게시글을 다시 조회하는 형태로 쪼개면 쿼리가 둘로 나뉘어
 * M0/M1이 재려던 대상 자체가 사라진다.
 *
 * <p>대신 <strong>결합의 방향과 깊이를 제한한다</strong>: {@code TimelinePullQuery}가 두 테이블을
 * native SQL로 <em>읽기만</em> 하고, {@code Post}·{@code Follow} 엔티티도
 * {@code PostRepository}·{@code FollowRepository}도 import하지 않는다. 그래서 이 패키지의 import 목록이
 * "timeline은 저 두 도메인의 자바 타입을 하나도 모른다"를 그대로 보여 준다.
 * 쓰기(fan-out, soft delete 반영)는 이 특례에 포함되지 않는다 — 그건 Phase 2a에서도 각 도메인의 몫이다.
 * (부록 B — Phase 1 구현 과정의 확정 결정)
 */
package com.timeline.timeline;
