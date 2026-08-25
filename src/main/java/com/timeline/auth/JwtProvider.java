package com.timeline.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 발급·검증. 토큰에 관한 모든 판단은 여기 한 곳에서만 일어난다.
 *
 * <p>클레임은 두 개뿐이다 — {@code sub = userId}, {@code type = ACCESS|REFRESH}.
 * username·nickname 같은 것을 넣지 않는 이유는 토큰이 <em>복사본</em>이기 때문이다.
 * 넣는 순간 30분짜리 stale 사본이 생기고, 닉네임을 바꿔도 토큰이 만료될 때까지 옛 값이 따라다닌다.
 * 필요한 것은 "누구인가"뿐이고 그건 userId로 충분하다.
 */
@Component
public class JwtProvider {

	private static final String CLAIM_TYPE = "type";

	private final SecretKey key;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	public JwtProvider(JwtProperties properties) {
		// 키 길이가 모자라면 여기서 WeakKeyException으로 기동이 실패한다.
		// 잘못된 설정이 "토큰은 발급되는데 안전하지 않은" 상태로 굴러가는 것보다 낫다.
		this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
		this.accessTokenTtl = properties.accessTokenTtl();
		this.refreshTokenTtl = properties.refreshTokenTtl();
	}

	public String createAccessToken(Long userId) {
		return create(userId, TokenType.ACCESS, accessTokenTtl);
	}

	public String createRefreshToken(Long userId) {
		return create(userId, TokenType.REFRESH, refreshTokenTtl);
	}

	/**
	 * 토큰을 검증하고 userId를 돌려준다. <strong>실패 사유를 구분하지 않는다.</strong>
	 *
	 * <p>서명 위조든 만료든 종류 불일치든 호출자가 할 일은 401 하나뿐이라,
	 * 사유를 구분해 봐야 그 정보는 공격자에게만 쓸모가 있다. 그래서 예외 대신 빈 값을 준다.
	 *
	 * @param expectedType 기대하는 토큰 종류. 다르면 유효한 서명이라도 거부한다
	 */
	public Optional<Long> parseUserId(String token, TokenType expectedType) {
		try {
			Claims claims = Jwts.parser()
					.verifyWith(key)
					.build()
					.parseSignedClaims(token)
					.getPayload();
			if (!expectedType.name().equals(claims.get(CLAIM_TYPE, String.class))) {
				return Optional.empty();
			}
			return Optional.of(Long.valueOf(claims.getSubject()));
		} catch (JwtException | IllegalArgumentException e) {
			// JwtException: 서명 불일치·만료·구조 손상. IllegalArgumentException: 빈 문자열, sub가 숫자가 아님.
			return Optional.empty();
		}
	}

	/**
	 * TTL을 직접 받는 발급 경로. <strong>package-private인 것이 이 메서드의 요점이다.</strong>
	 *
	 * <p>public이면 "만료된 토큰을 만드는 API"가 프로덕션 표면에 생긴다.
	 * 만료 검증 테스트에는 그런 토큰이 필요하므로 같은 패키지의 테스트에만 열어 둔다
	 * — Snowflake 백데이팅 팩토리를 더미 소스셋으로 격리한 것(&sect;4.2)과 같은 이유다.
	 */
	String create(Long userId, TokenType type, Duration ttl) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim(CLAIM_TYPE, type.name())
				// jti가 없으면 같은 초에 두 번 로그인한 사용자가 "같은 토큰"을 두 번 받는다
				// (iat·exp가 초 단위라 클레임이 전부 일치한다). 그러면 재로그인이 이전 토큰을
				// 무효화한다는 §5의 성질이 그 순간만 조용히 성립하지 않는다.
				.id(UUID.randomUUID().toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ttl)))
				.signWith(key)
				.compact();
	}
}
