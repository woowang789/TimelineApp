package com.timeline.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc-openapi 기본 정보. JWT 인증 스킴 등록은 0.8에서 함께 다룬다. */
@Configuration
public class SwaggerConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI().info(new Info()
				.title("타임라인 서비스 API")
				.version("v1"));
	}
}
