package com.timeline.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc-openapi 기본 정보 + Bearer 인증 스킴(0.8). */
@Configuration
public class SwaggerConfig {

	private static final String BEARER_SCHEME = "bearerAuth";

	/**
	 * 전역 보안 요구사항으로 Bearer를 걸어 둔다 — Swagger UI의 Authorize에 Access Token을 한 번 넣으면
	 * 이후 요청에 헤더가 붙는다. {@code /auth/**}는 permitAll이라 토큰 없이도 호출되므로,
	 * 문서상 자물쇠 표시가 붙는 것 외의 부작용은 없다.
	 */
	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("타임라인 서비스 API")
						.version("v1"))
				.components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}
}
