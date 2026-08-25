/**
 * 팔로우 도메인 — Phase 0에서는 경계만 잡는 빈 패키지다.
 *
 * <p>들어올 것: Follow 엔티티, FollowRepository, FollowController, FollowService. 0.9/0.10에서 채운다.
 *
 * <p>경계 규칙 1 — Follow 엔티티와 FollowRepository는 이 패키지 밖으로 노출하지 않는다.
 * 경계 규칙 2 — {@code timeline}이 이 패키지를 <strong>읽기만</strong> 한다. 그 반대는 없다.
 */
package com.timeline.follow;
