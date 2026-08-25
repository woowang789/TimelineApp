/**
 * 좋아요 도메인 — 좋아요 / 취소 (0.11).
 *
 * <p>Phase 0에서 만드는 것은 <strong>DB 쓰기 절반뿐이다.</strong> 좋아요 수를 {@code post:{postId}} 캐시로
 * 읽는 경로(마스터 &sect;4.4)는 Redis 캐시가 생기는 Phase 2a에 들어온다 — 이 패키지에 Redis 접근 코드는 없다.
 *
 * <p>경계 규칙 1 — Like 엔티티와 LikeRepository는 이 패키지 밖으로 노출하지 않는다.
 * 반대로 post 도메인에는 {@code PostService}로만 들어간다.
 */
package com.timeline.like;
