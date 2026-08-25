plugins {
	java
	// start.spring.io는 현재 4.x만 생성해 주지만, 프로젝트 규칙(CLAUDE.md)이 Spring Boot 3.x 고정이므로
	// 3.x 계열 최신 안정 버전으로 내려 고정한다.
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.timeline"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// 더미 데이터 생성 전용 소스셋 (마스터 §4.2 — 백데이팅 팩토리 격리).
// bootJar는 main 소스셋만 패키징하므로 dummy 클래스는 배포 산출물에 물리적으로 부재한다.
sourceSets {
	create("dummy") {
		java.srcDir("src/dummy/java")
		compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
		runtimeClasspath += output + compileClasspath
	}
}

// 0.13 선반영: of()와 nextId()의 비트 배치 일치를 테스트하려면 dummy 출력물이 test 클래스패스에 있어야 한다.
// bootJar는 main만 보므로 이 연결이 배포 산출물에 스며들지는 않는다.
sourceSets {
	test {
		compileClasspath += sourceSets["dummy"].output
		runtimeClasspath += sourceSets["dummy"].output
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-mysql")
	// initializr가 제공하지 않아 직접 추가 (Spring Boot 3.5 대응 2.x 최신)
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.0")

	// JWT (0.7). api / impl / jackson 3분할이 jjwt의 배포 형태다 —
	// 컴파일 의존은 api에만 걸고 구현체는 런타임에만 올린다. 그래야 애플리케이션 코드가
	// io.jsonwebtoken.impl.* 를 실수로 직접 참조할 수 없다.
	implementation("io.jsonwebtoken:jjwt-api:0.12.7")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.7")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.7")

	runtimeOnly("com.mysql:mysql-connector-j")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	// Testcontainers 실제 구성(싱글턴 컨테이너 · mysql:8.0 / redis:7 고정)은 0.6/0.13에서 작성한다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// test 클래스패스에 dummy 출력물이 올라가 있으므로 컴파일 선행을 보장한다 (0.13 선반영).
	dependsOn(tasks.named("compileDummyJava"))
}

// 더미 데이터 시드 (P1-01~P1-05). 사용법은 SeedMain의 클래스 주석 참고.
//   ./gradlew seed                              # 전체 · 풀 스케일
//   ./gradlew seed --args="all --scale=smoke"   # 축소 스케일
tasks.register<JavaExec>("seed") {
	group = "datagen"
	description = "더미 데이터 적재 (users → follows → posts → counts → cohorts)"
	mainClass = "com.timeline.datagen.SeedMain"
	// main 산출물이 아니라 dummy 소스셋으로 실행한다 — 백데이팅 팩토리가 거기에만 있다(마스터 §4.2).
	classpath = sourceSets["dummy"].runtimeClasspath
	// k6/data/cohorts.json 을 저장소 기준 경로로 쓰기 위해 작업 디렉토리를 고정한다.
	workingDir = rootDir
	// 슬롯 풀(300만) + 배정 결과가 힙에 올라간다. 실측 200MB 남짓이라 1G면 넉넉하다.
	maxHeapSize = "1g"
	// 진행률 로그가 파이프로 나갈 때 뭉치지 않게 한다 (야간 실행 시 tee 로 받는다).
	systemProperty("stdout.encoding", "UTF-8")
}
