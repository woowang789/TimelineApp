package com.timeline.auth;

/**
 * 로그인 응답 — 토큰 2종.
 *
 * <p>Access는 요청마다 {@code Authorization: Bearer} 헤더로 보내고,
 * Refresh는 Access가 만료됐을 때 {@code POST /api/v1/auth/reissue}에만 쓴다.
 */
public record LoginResponse(String accessToken, String refreshToken) {
}
