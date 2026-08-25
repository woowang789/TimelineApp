/**
 * 인증 도메인.
 *
 * <p>0.6 가입 → 0.7 로그인·재발급(JwtProvider · RefreshTokenRepository) →
 * 0.8 인가 경계(JwtAuthenticationFilter · JwtAuthenticationEntryPoint)까지 들어왔다.
 *
 * <p>토큰의 진실은 두 군데다 — 서명·만료·종류는 토큰 자신이 들고 있고(무상태),
 * "아직 유효한 세션인가"는 Redis {@code refresh:{userId}}가 들고 있다(&sect;5).
 * Access 검증에 Redis를 태우지 않는 것이 요점이다: 조회 98%(§9.1)인 서비스에서
 * 모든 요청이 Redis를 한 번씩 더 밟으면 그건 인증이 아니라 부하다.
 *
 * <p>경계 규칙 1 — 타 도메인({@code user} 등)은 Service 계층으로만 참조한다.
 */
package com.timeline.auth;
