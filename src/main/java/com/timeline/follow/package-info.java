/**
 * 팔로우 도메인.
 *
 * <p>0.6에서 Follow 엔티티 · FollowRepository · FollowService(가입 시 self-follow 행 삽입)가,
 * 0.9/0.10에서 FollowController · 팔로우/언팔로우(카운터 원자 증감 포함) · 팔로워/팔로잉 목록이 들어왔다.
 *
 * <p>경계 규칙 1 — Follow 엔티티와 FollowRepository는 이 패키지 밖으로 노출하지 않는다.
 * 반대 방향도 같다 — 목록 API가 상대방의 이름을 채울 때 follows JOIN users를 쓰지 않고
 * UserService에 id 목록으로 물어본다(UserSummary).
 * 경계 규칙 2 — {@code timeline}이 이 패키지를 <strong>읽기만</strong> 한다. 그 반대는 없다.
 */
package com.timeline.follow;
