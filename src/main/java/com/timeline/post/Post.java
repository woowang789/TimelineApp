package com.timeline.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 게시글 엔티티.
 *
 * <p><strong>id 에 생성 전략이 없다.</strong> {@code @GeneratedValue} 를 붙이지 않은 것은 누락이 아니라
 * 설계다 — Snowflake ID 를 애플리케이션이 만들어 대입한다(§4.2). 상위 41bit 가 타임스탬프여서
 * id 정렬이 곧 시간 정렬이고, 그 덕분에 Redis Sorted Set 의 score 와 커서 페이지네이션의 커서를
 * id 하나로 통일할 수 있다. DB 채번을 기다리지 않으므로 삽입 병목도 없다.
 * 생성기는 작업 0.5 에서 {@code common.snowflake} 에 구현한다.
 *
 * <p>다만 대가가 하나 있다 — id 가 미리 채워진 엔티티는 Hibernate 가 신규인지 기존인지 알 수 없어
 * {@code save()} 시 SELECT 를 한 번 먼저 던진다. 저장 경로를 만들 때(0.11) 다룰 문제로 남겨 둔다.
 *
 * <p><strong>User 를 참조하지 않고 {@code authorId} 를 {@code Long} 으로 직접 들고 있다</strong> —
 * 루트 {@code package-info} 의 경계 규칙 1(도메인 패키지 간 엔티티 직접 참조 금지).
 */
@Entity
@Table(name = "posts")
public class Post {

	/** Snowflake ID. 애플리케이션이 대입한다 — DB AUTO_INCREMENT 가 아니다. */
	@Id
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "author_id", nullable = false)
	private Long authorId;

	@Column(name = "content", nullable = false, length = 500)
	private String content;

	/** 비정규화 좋아요 수. SoT 는 DB 이고 원자적 UPDATE 로만 증감한다(부록 B) — 그래서 증감 메서드가 없다. */
	@Column(name = "like_count", nullable = false)
	private int likeCount;

	@Column(name = "is_deleted", nullable = false)
	private boolean isDeleted;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** JPA 전용. 직접 호출하지 말 것 — 생성은 {@link #create} 로만 한다. */
	protected Post() {
	}

	private Post(Long id, Long authorId, String content) {
		this.id = id;
		this.authorId = authorId;
		this.content = content;
		this.likeCount = 0;
		this.isDeleted = false;
		this.createdAt = LocalDateTime.now();
	}

	/**
	 * 게시글을 만든다. {@code id} 는 호출자가 Snowflake 생성기에서 받아 넘긴다 — 여기서 만들지 않는다.
	 */
	public static Post create(Long id, Long authorId, String content) {
		return new Post(id, authorId, content);
	}

	/**
	 * soft delete. 행을 지우지 않고 플래그만 세운다.
	 *
	 * <p>하드 삭제를 하지 않는 이유는 캐시에 있다 — 타임라인 Sorted Set 에는 post_id 만 들어 있어서
	 * 행이 사라지면 그 id 가 무엇이었는지 되짚을 방법이 없다. "25개 조회 → 삭제 필터 → 20개 반환"이라는
	 * 페이지 규약 자체가 이 플래그가 남아 있다는 전제 위에 서 있다.
	 */
	public void delete() {
		this.isDeleted = true;
	}

	public Long getId() {
		return id;
	}

	public Long getAuthorId() {
		return authorId;
	}

	public String getContent() {
		return content;
	}

	public int getLikeCount() {
		return likeCount;
	}

	public boolean isDeleted() {
		return isDeleted;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
