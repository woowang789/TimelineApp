/**
 * 게시글 도메인 — 작성 / 단건 조회 / soft delete / 작성자 글 목록 (0.11).
 *
 * <p>수정(`PATCH`)은 없다 — 마스터 &sect;3의 의도적 제외.
 * 삭제는 {@code is_deleted} 플래그만 세운다(하드 삭제 없음).
 *
 * <p>경계 규칙 1 — Post 엔티티와 PostRepository는 이 패키지 밖으로 노출하지 않는다.
 * like 도메인이 게시글 생사 확인·{@code like_count} 증감을 위해 들어오는 통로는 {@code PostService}뿐이다.
 * 경계 규칙 2 — {@code timeline}이 이 패키지를 <strong>읽기만</strong> 한다. 그 반대는 없다.
 */
package com.timeline.post;
