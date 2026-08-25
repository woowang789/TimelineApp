package com.timeline.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 사용자 엔티티. 스키마는 {@code V1__init_schema.sql} 이 유일한 관리자이고 여기서는 그것을 그대로 반영한다
 * (ddl-auto: validate 통과가 "그대로"의 판정 기준이다).
 *
 * <p><strong>연관관계 매핑을 쓰지 않는다.</strong> {@code @OneToMany} 로 follows·posts 를 걸면
 * user 패키지가 다른 도메인의 엔티티를 직접 참조하게 되어 루트 {@code package-info} 의 경계 규칙 1
 * ("Repository·엔티티를 타 도메인이 직접 참조하지 않는다")이 깨진다. 그래서 반대편 엔티티들이
 * FK 를 {@code Long} 필드로 들고 있고, 이쪽에서는 역방향 참조를 아예 두지 않는다.
 *
 * <p>setter 를 두지 않는다. 상태를 바꾸는 방법은 의도가 드러나는 메서드뿐이다.
 * 다만 {@code followerCount} 를 올리고 내리는 메서드는 여기에 없다 —
 * 카운터의 SoT 는 DB 이고 원자적 UPDATE 로만 증감하기로 확정했다(부록 B).
 * 엔티티로 읽고-더하고-쓰면 동시 팔로우에서 갱신 손실이 난다.
 */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "username", nullable = false, unique = true, length = 50)
	private String username;

	/** BCrypt 해시가 들어간다. 평문은 어떤 경로로도 이 필드에 닿지 않는다. */
	@Column(name = "password", nullable = false, length = 255)
	private String password;

	@Column(name = "nickname", nullable = false, length = 50)
	private String nickname;

	/** 비정규화 팔로워 수. self-follow 행은 세지 않으므로 가입 직후에도 0이다(§4.3). */
	@Column(name = "follower_count", nullable = false)
	private int followerCount;

	/** Hybrid 분기 플래그. 단방향 승격만 있고 강등은 없다(§7.2). 채우는 로직은 Phase 3. */
	@Column(name = "is_influencer", nullable = false)
	private boolean isInfluencer;

	/** 승격 시각 = Hybrid 머지 범위의 경계. 승격 전에는 null 이다. 채우는 로직은 Phase 3. */
	@Column(name = "influencer_since")
	private LocalDateTime influencerSince;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** JPA 전용. 직접 호출하지 말 것 — 생성은 {@link #create} 로만 한다. */
	protected User() {
	}

	private User(String username, String password, String nickname) {
		this.username = username;
		this.password = password;
		this.nickname = nickname;
		this.followerCount = 0;
		this.isInfluencer = false;
		this.influencerSince = null;
		this.createdAt = LocalDateTime.now();
	}

	/**
	 * 신규 가입자를 만든다. {@code password} 는 이미 해시된 값이어야 한다.
	 *
	 * <p>id 는 DB 가 채번하므로 persist 이후에야 값이 생긴다.
	 */
	public static User create(String username, String password, String nickname) {
		return new User(username, password, nickname);
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getNickname() {
		return nickname;
	}

	public int getFollowerCount() {
		return followerCount;
	}

	public boolean isInfluencer() {
		return isInfluencer;
	}

	public LocalDateTime getInfluencerSince() {
		return influencerSince;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
