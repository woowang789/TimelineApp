package com.timeline.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 임시 최소 Security 구성.
 *
 * <p><strong>JWT 인증 필터는 0.8(Security 인가 경계)에서 추가한다.</strong>
 * 지금은 부팅과 Swagger UI 접근이 가능한 최소 상태만 만든다 —
 * 토큰 발급(0.7)이 없는 시점이므로 인증 경로 자체가 아직 존재하지 않는다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// 토큰 기반 API이므로 CSRF 토큰을 쓰지 않는다.
				.csrf(AbstractHttpConfigurer::disable)
				// 서버는 세션을 만들지 않는다.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**", "/api/v1/auth/**")
						.permitAll()
						.anyRequest().authenticated());
		return http.build();
	}
}
