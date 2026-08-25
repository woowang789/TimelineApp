package com.timeline.support;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인가 경계 검증 전용 보호 엔드포인트 — <strong>테스트 소스셋에만 존재한다.</strong>
 *
 * <p>0.8이 검증해야 하는 것은 "미인증이면 401, 인증되면 통과"인데, 이 시점의 API는 전부
 * {@code /api/v1/auth/**}(permitAll)라 통과할 대상이 없다. 그렇다고 검증을 위해 main에
 * 엔드포인트를 만들면 스코프 밖의 API가 배포 산출물에 들어간다 — Snowflake 백데이팅 팩토리를
 * 더미 소스셋으로 뺀 것(&sect;4.2)과 같은 이유로, 검증 장치는 검증하는 쪽에 둔다.
 *
 * <p>덤으로 이 엔드포인트가 <strong>0.9~0.11이 쓸 인증 사용자 접근 방식</strong>을 미리 확정한다 —
 * {@code @AuthenticationPrincipal Long userId}. principal 자체가 userId(Long)라
 * 커스텀 리졸버도, 별도 UserDetails도 필요 없다.
 *
 * <p>{@code @TestConfiguration}은 컴포넌트 스캔에서 제외되므로(TypeExcludeFilter),
 * {@code IntegrationTestSupport}의 {@code @Import}로만 컨텍스트에 들어온다.
 */
@TestConfiguration
@RestController
public class ProtectedTestEndpoint {

	public static final String PATH = "/api/v1/__test/me";

	@GetMapping(PATH)
	public Map<String, Long> me(@AuthenticationPrincipal Long userId) {
		return Map.of("userId", userId);
	}
}
