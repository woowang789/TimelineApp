package com.timeline.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 접근 구성.
 *
 * <p>템플릿을 <strong>{@link StringRedisTemplate}으로 고정</strong>하는 것이 이 클래스의 전부다.
 * 기본 {@code RedisTemplate<Object, Object>}는 JDK 직렬화를 쓰기 때문에 키와 값에 바이너리 헤더가 붙고,
 * 그러면 {@code redis-cli}로 {@code refresh:1}을 조회했을 때 사람이 읽을 수 없는 값이 나온다.
 * 이 프로젝트는 Redis 안을 직접 들여다보며 측정·검증하는 것이 일과라(§5 키 설계 전반)
 * "저장된 그대로 보인다"가 편의가 아니라 작업 조건이다.
 *
 * <p>부트 자동구성도 같은 이름의 빈을 제공하지만(그래서 이 선언이 그것을 대체한다),
 * 자료구조 선택이 §5의 설계 결정인 이상 그 결정이 코드에서 보이는 편이 낫다.
 * Phase 2a의 타임라인 Sorted Set도 이 템플릿을 탄다.
 */
@Configuration
public class RedisConfig {

	@Bean
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}
}
