package com.timeline.like;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 좋아요 저장소.
 *
 * <p>경계 규칙 1에 따라 이 인터페이스는 {@code like} 패키지 밖으로 나가지 않는다.
 *
 * <p>쿼리를 {@code @Query}(JPQL)로 쓰지 않고 메서드 이름으로 파생시키는 이유가 하나 있다 —
 * 엔티티 이름이 {@code Like}인데 {@code LIKE}는 JPQL 예약어라 {@code "... FROM Like l ..."}을
 * 직접 쓰면 파서와 부딪힌다. 파생 쿼리는 Criteria API로 만들어져 이름 충돌이 없다.
 */
public interface LikeRepository extends JpaRepository<Like, Long> {

	/**
	 * 좋아요 취소.
	 *
	 * @return 지워진 행 수. 0이면 애초에 좋아요하지 않은 게시글이다.
	 */
	int deleteByPostIdAndUserId(Long postId, Long userId);
}
