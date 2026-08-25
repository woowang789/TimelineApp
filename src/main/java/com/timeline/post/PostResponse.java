package com.timeline.post;

import java.time.LocalDateTime;

/**
 * 게시글 응답.
 *
 * <p>{@link Post} 엔티티를 그대로 내보내지 않는다 — 엔티티가 응답 계약이 되면
 * 컬럼 하나 추가가 곧 API 변경이 되고, 경계 규칙 1(엔티티는 패키지 밖으로 나가지 않는다)도 깨진다.
 *
 * <p>{@code isDeleted}는 담지 않는다. 삭제된 글은 조회 자체가 404라서 이 응답으로 나올 일이 없다.
 */
public record PostResponse(Long id, Long authorId, String content, int likeCount, LocalDateTime createdAt) {

	static PostResponse from(Post post) {
		return new PostResponse(post.getId(), post.getAuthorId(), post.getContent(),
				post.getLikeCount(), post.getCreatedAt());
	}
}
