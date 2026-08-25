package com.timeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로드 스모크 테스트.
 *
 * <p>DB/Redis 없이 통과해야 하므로 {@code nodb} 프로파일을 쓴다.
 * 실제 MySQL/Redis를 띄우는 Testcontainers 기반 통합 테스트는 0.6에서 시작하고
 * 싱글턴 컨테이너 구성은 0.13에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class TimelineApplicationTests {

	@Test
	void contextLoads() {
	}
}
