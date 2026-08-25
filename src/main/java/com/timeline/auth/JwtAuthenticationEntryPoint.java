package com.timeline.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timeline.common.error.ErrorCode;
import com.timeline.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 미인증 요청의 응답을 만든다 (작업 0.8).
 *
 * <p>이 클래스가 있어야 하는 이유는 <strong>기본 동작이 302 리다이렉트</strong>이기 때문이다.
 * 스프링 시큐리티는 formLogin을 전제로 "로그인 페이지로 보내는" 진입점을 기본값으로 갖는데,
 * 로그인 페이지가 없는 토큰 API에서는 그게 클라이언트에게 "인증 실패"가 아니라
 * "어딘가로 가라"로 전달된다. 여기서 401 + {@link ErrorResponse} JSON으로 못 박는다 —
 * 형식은 {@code GlobalExceptionHandler}가 내는 에러 응답과 같다.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 시큐리티 필터 체인은 {@code @RestControllerAdvice} 바깥이라 예외가 핸들러에 닿지 않는다.
	 * 그래서 응답 본문을 직접 쓴다.
	 */
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(),
				ErrorResponse.of(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage()));
	}
}
