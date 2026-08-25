package com.timeline.support;

import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트 공통 기반 — 실제 MySQL 8.0 / Redis 7 컨테이너 위에서 돈다 (마스터 &sect;3).
 *
 * <p>H2나 embedded Redis로 바꾸지 않는다. 이 프로젝트가 검증하려는 것은 실행 계획·인덱스·
 * Redis 자료구조의 거동이고, 그건 대체 구현에서 재현되지 않는다.
 *
 * <p><strong>컨테이너는 싱글턴이다.</strong> {@code @Testcontainers}/{@code @Container}를 쓰면
 * 테스트 클래스마다 컨테이너가 재기동된다. Phase 0이 끝나면 통합 테스트가 10종이 되고
 * (0.13, 그리고 Phase 2b의 Pull/Push 동등성 검증이 CI 상시 실행으로 얹힌다),
 * MySQL 기동 시간 × 클래스 수가 그대로 CI 예산이 된다 — 목표는 스위트 5분 이내다.
 * 그래서 static 필드로 한 번만 띄우고 JVM 종료 시 Ryuk이 정리하게 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("timeline")
			.withUsername("timeline")
			.withPassword("timeline");

	// Redis를 지금 쓰는 경로는 없지만(refresh 토큰은 0.7) 컨테이너는 함께 띄운다 —
	// 자동구성이 붙는 대상이 실제 서버여야 "부팅은 됐는데 Redis만 없는" 상태를 만들지 않는다.
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
			.withExposedPorts(6379);

	static {
		MYSQL.start();
		REDIS.start();
	}

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	// 스키마는 여기서 만들지 않는다. application.yml의 Flyway 설정이 그대로 적용되어
	// V1__init_schema.sql이 이 컨테이너에도 실행된다 — 스키마 정의 경로를 하나로 유지해야
	// ddl-auto: validate가 의미를 갖는다. 테스트만 다른 스키마를 쓰면 validate는
	// "운영 스키마와 엔티티가 맞는가"가 아니라 "테스트 스키마와 엔티티가 맞는가"를 검사하게 된다.

	@Autowired
	protected TestRestTemplate restTemplate;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	/**
	 * 테스트 간 데이터 격리 — <strong>테이블 TRUNCATE 방식을 쓴다.</strong>
	 *
	 * <p>흔한 대안인 "테스트 메서드에 {@code @Transactional}을 걸어 롤백"은 여기서 쓸 수 없다.
	 * <ol>
	 *   <li>{@code webEnvironment = RANDOM_PORT}라 요청을 처리하는 것은 별도 스레드의 서버이고,
	 *       그 트랜잭션은 테스트 메서드의 트랜잭션과 아무 관계가 없다 — 롤백해도 남는다.</li>
	 *   <li>더 중요한 이유: 테스트를 트랜잭션으로 감싸면 커밋이 일어나지 않는다.
	 *       그런데 이 스위트가 확인하려는 것 중에는 UNIQUE·FK 제약처럼
	 *       <em>커밋(플러시) 시점에 DB가 판정하는 것</em>이 있다. 커밋을 막으면 그 검증이 사라진다.</li>
	 * </ol>
	 * 비용은 테스트당 TRUNCATE 몇 번인데, 행이 없다시피 한 테이블이라 무시할 만하다.
	 * (더미 데이터가 들어가는 Phase 1 이후의 측정용 DB는 이 경로와 무관하다.)
	 */
	@AfterEach
	void truncateAllTables() {
		// 테이블 목록을 코드에 박아 두면 마이그레이션이 테이블을 추가하는 순간
		// 조용히 격리가 깨진다. 스키마에 물어보는 편이 낫다.
		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
				String.class);

		// FK가 걸린 테이블은 TRUNCATE가 거부되므로 세션 단위로 검사를 끈다.
		// 세 문장이 반드시 같은 커넥션에서 실행되어야 해서 커넥션을 직접 잡는다 —
		// JdbcTemplate.execute()를 세 번 부르면 매번 풀에서 커넥션을 빌린다.
		jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET FOREIGN_KEY_CHECKS = 0");
				for (String table : tables) {
					statement.execute("TRUNCATE TABLE " + table);
				}
				statement.execute("SET FOREIGN_KEY_CHECKS = 1");
			}
			return null;
		});
	}
}
