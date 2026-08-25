/**
 * 팔로우 도메인.
 *
 * <p>0.6에서 Follow 엔티티 · FollowRepository · FollowService(가입 시 self-follow 행 삽입)까지 들어왔다.
 * 남은 것: FollowController와 팔로우/언팔로우·목록 — 0.9/0.10에서 채운다.
 *
 * <p>경계 규칙 1 — Follow 엔티티와 FollowRepository는 이 패키지 밖으로 노출하지 않는다.
 * 경계 규칙 2 — {@code timeline}이 이 패키지를 <strong>읽기만</strong> 한다. 그 반대는 없다.
 */
package com.timeline.follow;
