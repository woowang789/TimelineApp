/**
 * 인증 도메인.
 *
 * <p>0.6에서 회원가입(AuthController · AuthService · Signup DTO)까지 들어왔다.
 * 남은 것: JwtProvider, RefreshTokenRepository(Redis) — 0.7 로그인·재발급 / 0.8 인가 경계.
 *
 * <p>경계 규칙 1 — 타 도메인({@code user} 등)은 Service 계층으로만 참조한다.
 */
package com.timeline.auth;
