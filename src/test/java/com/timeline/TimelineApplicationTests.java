package com.timeline;

import com.timeline.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 컨텍스트 로드 스모크 테스트 — 로드맵 §1 통합 테스트 목록의 <strong>{@code SchemaValidationTest} 역할</strong>을 겸한다.
 *
 * <p><strong>0.6에서 {@code nodb} 프로파일을 걷어냈다.</strong> 그 프로파일은 DataSource·JPA 자동구성을
 * 빼서 "DB 없이 부팅되는지"를 봤는데, 가입 API가 들어오면서 UserRepository에 의존하는 빈이 생겼다 —
 * 자동구성을 빼면 그 빈들이 만들어지지 않으므로 DB 없는 부팅 자체가 성립하지 않는다
 * (테스트뿐 아니라 {@code SPRING_PROFILES_ACTIVE=nodb bootRun}도 같은 이유로 실패한다).
 * 그래서 부팅 확인의 자리를 실제 MySQL/Redis 위로 옮긴다 —
 * 이제 이 테스트는 Flyway가 만든 스키마에 대해 {@code ddl-auto: validate}가 통과하는지까지 함께 본다(C2).
 *
 * <p>컨테이너와 컨텍스트는 {@link IntegrationTestSupport}가 스위트 전체와 공유하므로 추가 비용은 없다.
 */
class TimelineApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}
}
