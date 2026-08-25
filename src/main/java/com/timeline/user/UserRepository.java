package com.timeline.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
