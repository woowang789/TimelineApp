package com.timeline.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사용자 저장소.
 *
 * <p>경계 규칙 1에 따라 이 인터페이스는 {@code user} 패키지 밖으로 나가지 않는다.
 * 타 도메인은 {@link UserService}만 본다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByUsername(String username);

	/** 로그인 검증용. username에 UNIQUE가 걸려 있어 인덱스 단건 조회다. */
	Optional<User> findByUsername(String username);

	/**
	 * 팔로워 수 +1 (마스터 &sect;4.4).
	 *
	 * <p><strong>엔티티를 읽고-더하고-쓰지 않는다.</strong> {@code user.followerCount + 1}을
	 * 애플리케이션에서 계산하면, 두 요청이 같은 값을 읽은 뒤 각자 +1 한 값을 쓰는 순간
	 * 한쪽 증가가 사라진다(갱신 손실). 동시에 팔로우가 들어오는 것은 정상 상황이지 예외가 아니다.
	 * DB에 {@code follower_count = follower_count + 1}을 통째로 맡기면 행 잠금이 직렬화해 주므로
	 * 낙관적 락도, 재시도도 필요 없다 — 카운터의 SoT를 DB로 정한 이유가 이것이다(부록 B).
	 *
	 * <p>호출자는 follows 행 삽입과 <strong>같은 트랜잭션</strong>에서 불러야 한다.
	 * 나뉘어 커밋되면 "팔로우는 됐는데 숫자는 그대로"인 상태가 영구히 남는다.
	 *
	 * @return 갱신된 행 수. 대상이 없으면 0이다.
	 */
	@Modifying
	@Query("UPDATE User u SET u.followerCount = u.followerCount + 1 WHERE u.id = :userId")
	int increaseFollowerCount(@Param("userId") Long userId);

	/** 팔로워 수 −1. 증가와 같은 이유로 원자적 UPDATE다({@link #increaseFollowerCount}). */
	@Modifying
	@Query("UPDATE User u SET u.followerCount = u.followerCount - 1 WHERE u.id = :userId")
	int decreaseFollowerCount(@Param("userId") Long userId);
}
