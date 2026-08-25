package com.timeline.post;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 게시글 저장소.
 *
 * <p>경계 규칙 1에 따라 이 인터페이스는 {@code post} 패키지 밖으로 나가지 않는다.
 * 타 도메인은 {@link PostService}만 본다.
 *
 * <p>모든 조회에 {@code is_deleted = false}가 붙는다. soft delete가 유일한 삭제 수단이므로
 * "행이 있다"와 "게시글이 살아 있다"가 다른 말이기 때문이다.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

	/** 살아 있는 게시글 1건. 삭제된 글은 여기서 걸러져 호출자에게 404가 된다. */
	@Query("SELECT p FROM Post p WHERE p.id = :postId AND p.isDeleted = false")
	Optional<Post> findActiveById(@Param("postId") Long postId);

	/**
	 * 작성자 글 목록 — <strong>단일 테이블 커서 쿼리</strong>다. JOIN도, OFFSET도 없다(마스터 &sect;6).
	 *
	 * <p>{@code id < :cursor} + {@code ORDER BY id DESC}가 커서 페이지네이션의 전부인 이유는
	 * Snowflake id의 상위 41bit가 타임스탬프라서다 — id 역순이 곧 최신순이고, 마지막으로 받은 id
	 * 하나만 있으면 다음 페이지의 시작점이 정해진다. OFFSET은 건너뛸 행을 매번 다시 읽으므로
	 * 뒤 페이지로 갈수록 비용이 커지고, 그사이 글이 추가되면 항목이 밀려 중복·누락이 생긴다.
	 *
	 * <p>이 쿼리를 받쳐 줄 보조 인덱스는 <strong>지금 만들지 않는다</strong> — 인덱스는 M0(인덱스 없음)
	 * 측정과 EXPLAIN 분석의 산출물로 Phase 1(P1-13)에서 도입한다(V1 마이그레이션 posts 블록 주석).
	 */
	@Query("SELECT p FROM Post p "
			+ "WHERE p.authorId = :authorId AND p.id < :cursor AND p.isDeleted = false "
			+ "ORDER BY p.id DESC")
	List<Post> findActiveByAuthorBefore(@Param("authorId") Long authorId, @Param("cursor") Long cursor, Limit limit);

	/**
	 * 좋아요 수 원자적 증가 (마스터 &sect;4.4).
	 *
	 * <p><strong>엔티티를 읽어서 +1 하고 저장하는 방식을 쓰지 않는다.</strong> 그렇게 하면 두 요청이
	 * 같은 값을 읽고 같은 값을 쓰는 순간 갱신 하나가 통째로 사라진다(lost update). DB가 행 락을 쥔 채
	 * 계산하는 {@code like_count = like_count + 1}에는 그 틈이 없다.
	 *
	 * <p>{@code is_deleted = false}를 조건에 넣어 <strong>게시글 생사 확인까지 이 한 문장이 겸한다</strong> —
	 * 별도 SELECT로 존재를 확인하면 확인과 UPDATE 사이에 삭제가 끼어들 수 있다.
	 *
	 * @return 갱신된 행 수. 0이면 없거나 이미 삭제된 게시글이다.
	 */
	@Modifying
	@Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId AND p.isDeleted = false")
	int increaseLikeCount(@Param("postId") Long postId);

	/** 좋아요 수 원자적 감소. 근거는 {@link #increaseLikeCount}와 같다. */
	@Modifying
	@Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :postId AND p.isDeleted = false")
	int decreaseLikeCount(@Param("postId") Long postId);
}
