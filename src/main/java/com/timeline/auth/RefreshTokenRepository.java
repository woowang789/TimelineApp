package com.timeline.auth;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token 저장소 — Redis {@code refresh:{userId}} String, TTL 14일 (마스터 &sect;5).
 *
 * <p><strong>키가 userId 단위이므로 사용자당 토큰은 1개다.</strong> 재로그인하면 SET이 덮어쓰고
 * 이전 Refresh Token은 그 순간 무효가 된다(재발급 시 저장값과 대조하기 때문이다).
 * 이것은 부수 효과가 아니라 의도된 동작이다 — 로그인 기기 수만큼 토큰이 쌓이지 않는다.
 *
 * <p>DB가 아니라 Redis에 두는 이유: TTL을 스토리지가 직접 강제하고, 만료된 행을 지우는
 * 배치가 필요 없다. 재발급은 조회 1회로 끝나므로 Redis 왕복 한 번이 전부다.
 */
@Repository
public class RefreshTokenRepository {

	private static final String KEY_PREFIX = "refresh:";

	private final StringRedisTemplate redisTemplate;
	private final Duration ttl;

	public RefreshTokenRepository(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
		this.redisTemplate = redisTemplate;
		// 키의 TTL과 토큰 자체의 만료를 같은 값에서 가져온다. 둘이 어긋나면
		// "Redis엔 있는데 토큰은 만료" 또는 그 반대의 상태가 생긴다.
		this.ttl = jwtProperties.refreshTokenTtl();
	}

	public void save(Long userId, String refreshToken) {
		redisTemplate.opsForValue().set(KEY_PREFIX + userId, refreshToken, ttl);
	}

	public Optional<String> findByUserId(Long userId) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + userId));
	}
}
