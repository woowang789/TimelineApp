package com.timeline.follow;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 팔로우 저장소.
 *
 * <p>경계 규칙 1에 따라 이 인터페이스는 {@code follow} 패키지 밖으로 나가지 않는다.
 * 타 도메인은 {@link FollowService}만 본다.
 *
 * <p><strong>users 를 JOIN 하지 않는다.</strong> 목록 API가 상대방의 username·nickname 을
 * 돌려주지만, 그 값은 여기서 JOIN 으로 끌어오지 않고 {@code UserService} 에 id 목록으로 물어본다.
 * 여기서 한 줄이면 되는 JOIN 을 참으면, follow 는 users 의 컬럼 구성을 모르는 상태로 남는다.
 */
public interface FollowRepository extends JpaRepository<Follow, Long> {

	boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

	/**
	 * 팔로우 행을 지운다.
	 *
	 * <p>파생 삭제({@code deleteBy...})는 엔티티를 먼저 SELECT 한 뒤 지운다. 여기서는 지울 행의
	 * 내용을 쓸 데가 없으므로 DELETE 한 문장으로 끝낸다. 반환값이 곧 "팔로우 중이었는가"의 답이라
	 * 존재 확인 조회를 따로 두지 않아도 되고, 그 사이에 낀 동시 언팔로우도 자연히 걸러진다.
	 *
	 * @return 삭제된 행 수. 0이면 팔로우 관계가 없었다는 뜻이다.
	 */
	@Modifying
	@Query("DELETE FROM Follow f WHERE f.followerId = :followerId AND f.followeeId = :followeeId")
	int deleteFollow(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

	/**
	 * 팔로워 목록 — {@code userId}를 팔로우하는 쪽의 행들을 최신순(id DESC)으로 읽는다.
	 *
	 * <p>{@code f.followerId <> f.followeeId}가 <strong>self-follow 행을 걷어내는 한 줄</strong>이다
	 * (&sect;4.3이 말하는 "대가"). 이 조건이 빠지면 모든 사용자의 팔로워 목록에 본인이 섞인다.
	 *
	 * <p>커서는 {@code f.id}이고 OFFSET을 쓰지 않는다(&sect;6). {@code cursor}에 null 대신
	 * {@link Long#MAX_VALUE}를 받는 이유는 {@code (:cursor IS NULL OR f.id < :cursor)} 형태를 피하려는 것이다 —
	 * 그러면 첫 페이지든 다음 페이지든 술어가 {@code id < ?} 하나로 같아서 인덱스 진입 방식이 흔들리지 않는다.
	 *
	 * @param cursor 이 값보다 <strong>작은</strong> id부터 읽는다(exclusive). 첫 페이지는 {@link Long#MAX_VALUE}
	 */
	@Query("SELECT f FROM Follow f "
			+ "WHERE f.followeeId = :userId "
			+ "AND f.followerId <> f.followeeId "
			+ "AND f.id < :cursor "
			+ "ORDER BY f.id DESC")
	List<Follow> findFollowers(@Param("userId") Long userId, @Param("cursor") Long cursor, Limit limit);

	/**
	 * 팔로잉 목록 — {@code userId}가 팔로우하는 쪽의 행들을 최신순(id DESC)으로 읽는다.
	 *
	 * <p>{@link #findFollowers}와 조건이 하나만 다르다({@code followeeId =} → {@code followerId =}).
	 * 그 한 글자가 방향이다. self-follow 제외와 커서 규칙은 동일하다.
	 */
	@Query("SELECT f FROM Follow f "
			+ "WHERE f.followerId = :userId "
			+ "AND f.followerId <> f.followeeId "
			+ "AND f.id < :cursor "
			+ "ORDER BY f.id DESC")
	List<Follow> findFollowings(@Param("userId") Long userId, @Param("cursor") Long cursor, Limit limit);
}
