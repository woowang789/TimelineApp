package com.timeline.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 좋아요 엔티티.
 *
 * <p><strong>Post·User 를 참조하지 않고 FK 를 {@code Long} 으로 직접 들고 있다</strong> —
 * 루트 {@code package-info} 의 경계 규칙 1(도메인 패키지 간 엔티티 직접 참조 금지).
 *
 * <p>중복 좋아요는 DB 의 {@code uk_likes_post_user} 가 막는다. 애플리케이션에서 존재 여부를
 * 먼저 조회해 거르는 방식은 동시 요청에서 뚫린다 — 제약을 신뢰하고 위반을 잡는다.
 *
 * <p>게시글이 soft delete 되어도 좋아요 행은 남는다. {@code posts} 에 하드 삭제가 없으므로
 * FK 가 끊길 일이 없다.
 */
@Entity
@Table(name = "likes")
public class Like {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id", nullable = false)
	private Long postId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** JPA 전용. 직접 호출하지 말 것 — 생성은 {@link #create} 로만 한다. */
	protected Like() {
	}

	private Like(Long postId, Long userId) {
		this.postId = postId;
		this.userId = userId;
		this.createdAt = LocalDateTime.now();
	}

	public static Like create(Long postId, Long userId) {
		return new Like(postId, userId);
	}

	public Long getId() {
		return id;
	}

	public Long getPostId() {
		return postId;
	}

	public Long getUserId() {
		return userId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
