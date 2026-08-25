package com.timeline.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정값 (application.yml {@code jwt.*}).
 *
 * <p>TTL 두 개는 마스터 부록 B의 확정값이다 — Access 30분 / Refresh 14일.
 * 코드에 상수로 박지 않고 설정으로 빼 둔 이유는 <strong>테스트가 실측하기 위해서</strong>다:
 * {@code LoginIntegrationTest}가 Redis TTL을 이 값과 대조한다.
 *
 * @param secret          Base64 인코딩된 HMAC 키. 디코딩 결과가 32바이트 미만이면 기동 시 실패한다.
 * @param accessTokenTtl  Access Token 유효 기간
 * @param refreshTokenTtl Refresh Token 유효 기간 = Redis {@code refresh:{userId}} 키의 TTL
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
}
