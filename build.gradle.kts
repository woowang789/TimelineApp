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
