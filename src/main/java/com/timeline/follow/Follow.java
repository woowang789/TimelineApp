package com.timeline.follow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 팔로우 관계 엔티티.
 *
 * <p><strong>User 를 참조하지 않고 FK 를 {@code Long} 으로 직접 들고 있다.</strong>
 * {@code @ManyToOne User} 로 걸면 follow 패키지가 user 패키지의 엔티티에 컴파일 의존하게 되어
 * 루트 {@code package-info} 의 경계 규칙 1을 위반한다. 성능상의 이유도 겹치는데,
 * fan-out 대상 조회(Phase 2a)는 팔로워 <em>id 목록</em>만 필요하다 —
 * 연관관계를 걸어 두면 그 자리에서 User 를 통째로 로딩하려는 코드가 반드시 생긴다.
 *
 * <p><strong>self-follow 행</strong>({@code followerId == followeeId})이 가입 시 1건 삽입된다(§4.3).
 * Pull(JOIN)과 Push(직접 ZADD)의 결과 집합을 같게 만들어 Phase 간 비교를 성립시키는 장치다.
 * 이 행은 시스템 불변식이라 API 로 만들거나 지울 수 없고(400), 팔로워/팔로잉 목록과
 * {@code follower_count} 에서는 {@code followerId != followeeId} 로 제외한다.
 */
@Entity
@Table(name = "follows")
public class Follow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 팔로우 하는 쪽. */
	@Column(name = "follower_id", nullable = false)
	private Long followerId;

	/** 팔로우 당하는 쪽. */
	@Column(name = "followee_id", nullable = false)
	private Long followeeId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** JPA 전용. 직접 호출하지 말 것 — 생성은 {@link #create} 로만 한다. */
	protected Follow() {
	}

	private Follow(Long followerId, Long followeeId) {
		this.followerId = followerId;
		this.followeeId = followeeId;
		this.createdAt = LocalDateTime.now();
	}

	/**
	 * 팔로우 관계를 만든다. 가입 시 삽입하는 self-follow 행도 이 팩토리로 만든다
	 * ({@code create(userId, userId)}).
	 *
	 * <p>중복은 DB 의 {@code uk_follows_follower_followee} 가 막는다.
	 */
	public static Follow create(Long followerId, Long followeeId) {
		return new Follow(followerId, followeeId);
	}

	public Long getId() {
		return id;
	}

	public Long getFollowerId() {
		return followerId;
	}

	public Long getFolloweeId() {
		return followeeId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
