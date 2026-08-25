package com.timeline.support;

import com.timeline.auth.LoginResponse;
import com.timeline.auth.SignupResponse;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
@Import(ProtectedTestEndpoint.class)
public abstract class IntegrationTestSupport {

	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("timeline")
			.withUsername("timeline")
			.withPassword("timeline");

	// 0.7부터 Refresh Token이 여기 들어간다. 자동구성이 붙는 대상이 실제 서버여야
	// "부팅은 됐는데 Redis만 없는" 상태를 만들지 않는다.
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

	@Autowired
	protected StringRedisTemplate redisTemplate;

	/**
	 * 가입 + 로그인. 인증이 필요한 테스트의 준비 단계이고, 0.9~0.11의 모든 테스트가 이 자리에서 시작한다.
	 *
	 * <p>토큰을 얻는 경로를 로그인 API로 두는 이유는, 테스트가 {@code JwtProvider}로 토큰을 직접 만들면
	 * "발급은 되는데 로그인이 깨진" 상태를 이 스위트가 못 잡기 때문이다.
	 */
	protected Tokens signupAndLogin(String username, String password) {
		Long userId = restTemplate.postForEntity("/api/v1/auth/signup",
				Map.of("username", username, "password", password, "nickname", username),
				SignupResponse.class).getBody().id();

		LoginResponse tokens = restTemplate.postForEntity("/api/v1/auth/login",
				Map.of("username", username, "password", password),
				LoginResponse.class).getBody();

		return new Tokens(userId, tokens.accessToken(), tokens.refreshToken());
	}

	/** {@code Authorization: Bearer ...} 헤더 하나짜리 요청 엔티티. */
	protected HttpEntity<Void> bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return new HttpEntity<>(headers);
	}

	/** 가입·로그인 한 사용자의 식별자와 토큰 묶음. */
	protected record Tokens(Long userId, String accessToken, String refreshToken) {
	}

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

	/**
	 * Redis 격리 — 키 공간을 통째로 비운다.
	 *
	 * <p>{@code refresh:{userId}} 키의 TTL이 14일이라 지우지 않으면 스위트가 끝날 때까지 남는다.
	 * userId는 TRUNCATE로 1부터 다시 채번되므로, 남은 키는 다음 테스트가 만든 사용자에게
	 * <strong>이전 테스트의 Refresh Token으로 붙는다</strong> — "재발급이 되네"가 통과해 버리는 종류의 오염이다.
	 */
	@AfterEach
	void flushRedis() {
		try (RedisConnection connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
			connection.serverCommands().flushAll();
		}
	}
}
