package com.timeline.timeline;

import java.time.LocalDateTime;

/**
 * 타임라인 항목 응답 (마스터 &sect;6).
 *
 * <p><strong>post 패키지의 {@code PostResponse}를 재사용하지 않는다.</strong> 필드 구성이 지금은 같지만,
 * 그건 우연이지 계약이 아니다 — 재사용하면 게시글 단건 응답을 바꾸는 순간 타임라인 응답이 함께 바뀌고,
 * 그 반대도 마찬가지다. 게다가 {@code PostResponse.from(Post)}는 엔티티를 인자로 받으므로
 * 이 패키지가 그 타입을 쓰려면 {@code Post}까지 끌고 와야 한다(경계 규칙 1 위반).
 *
 * <p>필드 이름은 {@code PostResponse}와 <em>의도적으로</em> 맞춰 둔다. Phase 2b의 Pull==Push
 * 동등성 테스트(&sect;9.4)가 두 경로의 응답을 postId 시퀀스로 대조하는데, 이름이 어긋나면
 * 그 비교가 응답 형식 차이부터 걸러 내야 한다.
 *
 * <p>{@code isDeleted}는 담지 않는다 — 삭제된 글은 조회 SQL의 {@code is_deleted = false}에서 이미 빠진다.
 */
public record TimelineItem(Long id, Long authorId, String content, int likeCount, LocalDateTime createdAt) {
}
