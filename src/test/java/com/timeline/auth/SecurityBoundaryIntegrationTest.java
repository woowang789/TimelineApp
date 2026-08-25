package com.timeline.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.support.IntegrationTestSupport;
import com.timeline.support.ProtectedTestEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 인가 경계 통합 테스트 (작업 0.8).
 *
 * <p>이 클래스가 실제로 막는 회귀는 <strong>"미인증인데 302"</strong>다. formLogin 기본값이 살아 있으면
 * 스프링 시큐리티는 인증 실패를 로그인 페이지로의 리다이렉트로 표현하고, 그러면 클라이언트는
 * 401을 받은 적이 없으므로 재발급을 시도하지도 않는다. 상태 코드만이 아니라
 * <strong>본문이 JSON 에러 응답인지</strong>까지 보는 이유다.
 *
 * <p>검증 대상 보호 경로는 {@link ProtectedTestEndpoint} — 이 시점의 main에는 보호 API가 없다.
 */
class SecurityBoundaryIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("토큰 없이 보호 경로에 접근하면 리다이렉트가 아니라 401 + JSON이다")
	void rejectsUnauthenticatedRequestWithJson() throws Exception {
		ResponseEntity<String> response = restTemplate.getForEntity(ProtectedTestEndpoint.PATH, String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 3xx도, Location 헤더도 없어야 한다 — 로그인 페이지로 보내는 경로가 살아 있으면 여기서 걸린다.
		assertThat(response.getHeaders().getLocation()).isNull();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
		assertThat(body.get("message").asText()).isNotBlank();
	}

	@Test
	@DisplayName("깨진 토큰도 401 + JSON이다 — 필터가 직접 거절하지 않고 진입점이 응답을 만든다")
	void rejectsMalformedToken() throws Exception {
		ResponseEntity<String> response = restTemplate.exchange(ProtectedTestEndpoint.PATH,
				HttpMethod.GET, bearer("not.a.token"), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(objectMapper.readTree(response.getBody()).get("code").asText()).isEqualTo("UNAUTHORIZED");
	}

	@Test
	@DisplayName("유효한 Access Token이면 통과하고, principal로 userId가 들어온다")
	void allowsAuthenticatedRequest() throws Exception {
		Tokens tokens = signupAndLogin("alice", "password123");

		ResponseEntity<String> response = restTemplate.exchange(ProtectedTestEndpoint.PATH,
				HttpMethod.GET, bearer(tokens.accessToken()), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// 통과만이 아니라 "누구로 통과했는가"까지 본다 — 0.9~0.11이 이 값으로 동작한다.
		assertThat(objectMapper.readTree(response.getBody()).get("userId").asLong()).isEqualTo(tokens.userId());
	}

	@Test
	@DisplayName("Swagger와 Actuator는 토큰 없이 열린다")
	void allowsPublicPaths() {
		// Swagger는 C5(§1 완료 조건)가 브라우저로 확인하는 경로이고, Actuator는 bench 프로파일의
		// Prometheus가 스크레이프하는 경로다. 둘 중 하나라도 401이면 그 검증·측정이 막힌다.
		assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}
}
