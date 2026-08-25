package com.timeline.follow;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 팔로우 저장소.
 *
 * <p>경계 규칙 1에 따라 이 인터페이스는 {@code follow} 패키지 밖으로 나가지 않는다.
 * 타 도메인은 {@link FollowService}만 본다.
 */
public interface FollowRepository extends JpaRepository<Follow, Long> {
}
