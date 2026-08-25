package com.timeline.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Access Token → SecurityContext 인증 (작업 0.8).
 *
 * <p><strong>이 필터는 거절하지 않는다.</strong> 토큰이 없거나 깨졌으면 인증을 세팅하지 않고 통과시키고,
 * 그 요청이 보호 경로였는지 판단하는 것은 뒤의 인가 단계다. 필터가 직접 401을 쓰면
 * permitAll 경로에 이상한 토큰을 붙인 요청까지 막히고, 거절 응답을 만드는 자리가
 * {@link JwtAuthenticationEntryPoint}와 둘로 갈라진다.
 *
 * <p>스프링 빈으로 등록하지 않고 {@code SecurityConfig}에서 직접 생성해 체인에 넣는다.
 * {@code Filter} 타입 빈은 부트가 서블릿 컨테이너에도 자동 등록해서 시큐리티 체인 밖에서 한 번 더 도는데,
 * 그러면 같은 파싱이 요청당 두 번 일어난다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtProvider jwtProvider;

	public JwtAuthenticationFilter(JwtProvider jwtProvider) {
		this.jwtProvider = jwtProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			// ACCESS만 받는다 — Refresh Token으로 API를 호출할 수 있으면 TTL 30분을 정한 의미가 없다.
			jwtProvider.parseUserId(header.substring(BEARER_PREFIX.length()), TokenType.ACCESS)
					.ifPresent(userId -> SecurityContextHolder.getContext().setAuthentication(
							// principal이 곧 userId(Long)다. 컨트롤러는 @AuthenticationPrincipal Long으로 받는다.
							// 권한 개념이 없는 서비스라 authorities는 빈 목록이다.
							new UsernamePasswordAuthenticationToken(userId, null, List.of())));
		}
		filterChain.doFilter(request, response);
	}
}
