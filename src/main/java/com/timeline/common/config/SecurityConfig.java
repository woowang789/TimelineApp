package com.timeline.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.auth.JwtAuthenticationEntryPoint;
import com.timeline.auth.JwtAuthenticationFilter;
import com.timeline.auth.JwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 인가 경계 (작업 0.8).
 *
 * <p>기본값을 세 군데 끈다 — 세션, formLogin, httpBasic. 셋 다 "서버가 로그인 상태를 기억하고
 * 브라우저가 로그인 폼을 본다"는 전제를 깔고 있는데, 이 API의 로그인 상태는 전부 토큰 안에 있다.
 * 특히 formLogin을 켜 둔 채로 두면 미인증 요청이 401이 아니라 <strong>로그인 페이지로 302</strong>된다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * 인증 없이 열어 두는 경로.
	 *
	 * <ul>
	 *   <li>{@code /api/v1/auth/**} — 토큰을 발급받는 경로다. 여기에 토큰을 요구하면 순환이다</li>
	 *   <li>Swagger 2종 — 문서다. 로컬 전용 실행이라 노출 위험이 없다(C5가 이걸 확인한다)</li>
	 *   <li>{@code /actuator/**} — bench 프로파일의 Prometheus가 스크레이프한다.
	 *       인증을 걸면 측정 인프라에 자격증명을 심어야 한다. 노출 엔드포인트 자체를
	 *       health·info·prometheus·metrics로 제한하는 방식(application.yml)을 택했다</li>
	 * </ul>
	 */
	private static final String[] PUBLIC_PATHS = {
			"/api/v1/auth/**", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/**"
	};

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
			ObjectMapper objectMapper) throws Exception {
		http
				// 토큰 기반 API이므로 CSRF 토큰을 쓰지 않는다.
				.csrf(AbstractHttpConfigurer::disable)
				// 서버는 세션을 만들지 않는다.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						// 명시적으로 열지 않은 것은 전부 인증 대상이다. 새 API가 생겨도 기본값이 "보호"다.
						.anyRequest().authenticated())
				// 인가 판단 전에 토큰을 읽어 SecurityContext를 채운다.
				.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(handler ->
						handler.authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper)));
		return http.build();
	}

	/**
	 * 비밀번호 해시. BCrypt 기본 강도(10)를 그대로 쓴다 — 강도를 올리면 해시 비용이 올라가고,
	 * 그 비용은 로그인 응답 시간에 그대로 실린다(&sect;9.3의 "매 요청 로그인하면 BCrypt가 병목" 경고).
	 * 그건 측정 설계에서 다룰 문제이고, 여기서는 표준 구현만 한다.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
