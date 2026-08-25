/**
 * 인증 도메인 — Phase 0에서는 경계만 잡는 빈 패키지다.
 *
 * <p>들어올 것: AuthController, AuthService, JwtProvider, RefreshTokenRepository(Redis).
 * 0.6 회원가입 / 0.7 로그인·재발급 / 0.8 인가 경계에서 채운다.
 *
 * <p>경계 규칙 1 — 타 도메인({@code user} 등)은 Service 계층으로만 참조한다.
 */
package com.timeline.auth;
