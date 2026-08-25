# Phase 0 — 기반 구축 (W1~W2 · 2026-08-24 ~ 2026-09-06 · 선행: 없음)

> 마스터 문서: `../../timeline-project.md` — 이 문서는 마스터 §8 Phase 0 체크박스와 부록 A(첫 주 6단계)의 실행 계획이다.
> 마스터의 결정을 재론하지 않는다. 결정의 근거는 마스터의 해당 절을 참조한다.

| 항목 | 내용 |
|---|---|
| 기간 | W1~W2, 2주 (2026-08-24 ~ 2026-09-06) |
| 주차 | W1: 2026-08-24 ~ 2026-08-30 · W2: 2026-08-31 ~ 2026-09-06 |
| 선행 Phase | 없음 (프로젝트 시작점) |
| 다음 Phase | [Phase 1 — 더미 데이터 & 첫 측정](./20-phase-1-baseline.md) (W3~W5) |
| 전체 로드맵 | [개요](./00-overview.md) |
| 마스터 근거 | §8 Phase 0 · 부록 A · §3 (실행 구성) · §4 (도메인 모델) |

---

## 1. 목표와 완료 조건

### 목표

마스터 §8 Phase 0의 완료 조건 그대로 —

> **"기능은 다 되지만 데이터가 없고 성능 개념도 없는 상태"**

를 2주 안에 만든다. Phase 1(→ [./20-phase-1-baseline.md](./20-phase-1-baseline.md))부터는 측정이 시작되므로,
Phase 0에서는 기능·인프라·테스트 기반을 전부 닫되 **성능 작업은 일절 하지 않는다.**
M0가 "인덱스 없음, 최악 baseline"이어야 하므로(§9.2) 이 단계에서의 선(先)최적화는 곧 서사 훼손이다.

### 완료 조건 — 전부 검증 가능한 형태로

| # | 조건 | 검증 방법 |
|---|---|---|
| C1 | 아래 "통합 테스트 통과 목록" 전부 green | `./gradlew test` 로컬 통과 + GitHub Actions 통과 |
| C2 | Flyway V1 스키마 = JPA 엔티티 일치 | `ddl-auto: validate` 상태에서 애플리케이션 부팅 성공 |
| C3 | Docker Compose 3종 기동 + 메모리 상한 준수 | `docker stats`로 MySQL 2G / Redis 1.5G / (bench) Prometheus 0.4G + Grafana 0.3G / (async) Kafka 1.2G 상한 확인, `redis-cli CONFIG GET maxmemory` = 1gb, `maxmemory-policy` = allkeys-lru |
| C4 | Snowflake 백데이팅 팩토리가 배포 산출물에서 격리됨 | `unzip -l build/libs/*.jar`에 dummy 소스셋 클래스 없음 (CI 단계로 자동화) |
| C5 | §6의 Phase 0 범위 API가 Swagger에 노출 | `/swagger-ui/index.html`에서 auth/users/follow/posts/likes 엔드포인트 확인 (`GET /timeline` 미구현이 정상 — 작성자 글 목록 `GET /users/{userId}/posts`는 0.11에서 구현됨) |
| C6 | CI가 push/PR마다 통합 테스트를 실행 | GitHub Actions 워크플로 green run 존재 |
| C7 | "데이터 없음·성능 개념 없음" 유지 | 더미 데이터 생성 코드 없음, 성능 목적 인덱스 추가·캐시 조회 경로 없음 (`GET /timeline` 미구현 상태) |

### 통합 테스트 통과 목록 (C1의 실체)

Testcontainers(실제 MySQL 8.0 + Redis 7, §3) 기반. 이 목록이 Phase 0의 "기능은 다 된다"의 증거다.

| 테스트 | 검증 내용 | 근거 |
|---|---|---|
| `SignupIntegrationTest` | 가입 → users 행 생성 + **self-follow 행 1건 삽입** + `follower_count = 0` (self 제외) | §4.3 |
| `LoginIntegrationTest` | 로그인 → Access/Refresh 발급, Redis `refresh:{userId}` 존재 + TTL ≈ 14일 | §5, §6 |
| `ReissueIntegrationTest` | 정상 refresh로 재발급 성공 / 위조·만료 refresh 거부 | §6 |
| `FollowIntegrationTest` | 팔로우 → `follower_count` +1 (원자 UPDATE), 중복 팔로우 UNIQUE 거부, 언팔로우 → −1 | §4.1, §4.4 |
| `FollowListIntegrationTest` | followers/followings 목록에서 **self-follow 행 제외**, 커서 페이지네이션 응답 형식(`data`/`nextCursor`/`hasNext`) | §4.3, §6 |
| `PostIntegrationTest` | 작성(Snowflake ID 부여) → 단건 조회 → soft delete → 조회 404, DB 행은 `is_deleted = true`로 존속 | §4.1, §6 |
| `AuthorPostsIntegrationTest` | `GET /users/{userId}/posts` 커서 페이지네이션, 삭제 글 제외 | §6 |
| `LikeIntegrationTest` | 좋아요 → likes 행 삽입 + `like_count` +1이 **같은 트랜잭션**, 중복 좋아요 거부, 취소 → −1 | §4.4 |
| `SchemaValidationTest` | Flyway 마이그레이션 적용 후 `ddl-auto: validate`로 컨텍스트 로드 성공 | §3 |
| `SnowflakeIdTest` (단위) | 단조 증가, ms당 4,096 시퀀스 초과 시 다음 ms 대기, ID→타임스탬프 복원 | §4.2 |

---

## 2. 선행 조건

첫 Phase이므로 **이전 Phase 산출물은 없다.** 대신 착수 전 갖춰야 할 것:

| 항목 | 내용 | 확인 |
|---|---|---|
| 마스터 문서 확정 | `../../timeline-project.md` 부록 B의 결정 요약이 최종 상태 | 문서 리뷰 완료 |
| 측정 머신 | Apple M1 · 8코어 · RAM 8GB · 디스크 여유 55GB (§2) | `system_profiler` / `df -h` |
| Docker Desktop | **CPU 5 / 메모리 5GB** 할당 (§3) | Docker Desktop 설정 화면 |
| JDK 21 | 호스트 실행용 (§3 — 애플리케이션은 호스트에서 `-Xmx1g`) | `java -version` |
| GitHub 계정 | 저장소·Actions 사용 | — |

---

## 3. 작업 분해

- 실행 단위는 반나절(0.5일)~1일. 주당 실작업 6일(D1~D6)로 잡고 남는 날은 버퍼다.
- 각 작업의 "검증:"이 충족되어야 완료다. 검증 불가능한 상태로 다음 작업으로 넘어가지 않는다.
- **W1은 마스터 부록 A의 6단계와 1:1로 정렬한다.** "6번까지 되면 나머지는 반복이다"(부록 A) — 0.6이 W1의 데드라인이다.

### W1 (2026-08-24 ~ 2026-08-30) — 부록 A 6단계

| 번호 | 부록 A | 일차 | 작업 | 상세 | 검증 |
|---|---|---|---|---|---|
| 0.1 | A-1 | D1 오전 | GitHub 저장소 생성 + README 뼈대 | 마스터 §10의 목차(한 줄 소개 / 다른 점 / 아키텍처 / 기술적 도전 / 측정 방법 / 일반화 / 의도적으로 하지 않은 것 / 실행 방법)를 빈 절로 배치. `docs/` 디렉토리 구조 생성 | 검증: README에 §10 목차 8개 절이 존재, 첫 커밋 push 완료 |
| 0.2 | A-2 | D1 오후 ~ D2 | Spring Boot 프로젝트 생성 + 패키지 구조 | Java 21 / Spring Boot 3.x. 의존성: Web, Data JPA, Data Redis, Security, Flyway(+mysql), Validation, Actuator, Micrometer(prometheus registry), springdoc-openapi, Testcontainers(mysql·junit). 본문 4.1절의 패키지 트리 수립(빈 패키지 포함) | 검증: `./gradlew build` 성공, 부팅 성공(아직 DB 미연결 프로파일), Swagger UI 응답 |
| 0.3 | A-3 | D3 | `compose.yml` (dev 프로파일) | MySQL 8.0(컨테이너 메모리 2G) + Redis 7(1.5G). Redis 기동 옵션 `--maxmemory 1gb --maxmemory-policy allkeys-lru` (§3, §5-5). MySQL 볼륨 지정(Phase 1의 스냅샷 복원 §9.3 대비) | 검증: `docker compose up -d` 후 `docker stats` 상한 2G/1.5G, `redis-cli CONFIG GET maxmemory` = 1073741824, policy = allkeys-lru |
| 0.4 | A-4 | D4 | Flyway V1 마이그레이션 + `ddl-auto: validate` | `V1__init_schema.sql`에 users/follows/posts/likes 작성 — `influencer_since` 포함, **posts 조회용 보조 인덱스 미포함**(§4.1 주석, 상세 → 본문 4.3절). UNIQUE·FK 제약과 follows `(followee_id, follower_id)` 인덱스는 포함. JPA 엔티티 4종 작성, `spring.jpa.hibernate.ddl-auto: validate` 고정 | 검증: 빈 DB에 `flyway migrate` 성공 → validate 상태로 부팅 성공, posts에 보조 인덱스 부재 확인(`SHOW INDEX FROM posts`). 엔티티가 DB에 없는 컬럼을 가리키게 하면 부팅 실패하는 것 확인 후 원복 (validate는 엔티티→DB 방향만 검사하므로 "엔티티에서 컬럼을 빼는" 실험은 실패하지 않는다 — 0.4 실측) |
| 0.5 | A-5 | D5 | Snowflake ID 생성기 + 더미 소스셋 격리 | 프로덕션은 `nextId()`만 public. `of(epochMilli, nodeId, sequence)` 팩토리는 Gradle `dummy` 소스셋에만 배치(구성 → 본문 4.6절). `SnowflakeIdTest` 작성 | 검증: `SnowflakeIdTest` green, main 소스에서 `of(...)` 참조 시 컴파일 에러, `bootJar` 산출물에 dummy 클래스 부재 |
| 0.6 | A-6 | D6 | 회원가입 API + **self-follow 행 삽입** + Testcontainers 통합 테스트 1개 | `POST /auth/signup` — BCrypt 해시 저장, **같은 트랜잭션에서 self-follow 행(follower_id = followee_id) 1건 삽입**, 이때 `follower_count`는 증가시키지 않음(§4.1 "self-follow 제외") | 검증: `SignupIntegrationTest` green — follows에 self 행 1건, `follower_count = 0` |

**W1 마일스톤**: 0.6까지 완료 = 부록 A 6단계 완주. 여기까지 되면 "나머지는 반복"이다.

### W2 (2026-08-31 ~ 2026-09-06) — 나머지 기능 + 인프라 마감

| 번호 | 일차 | 작업 | 상세 | 검증 |
|---|---|---|---|---|
| 0.7 | D1 | JWT 로그인 / 재발급 | `POST /auth/login` → Access + Refresh 발급, Refresh는 Redis `refresh:{userId}` String **TTL 14일**(§5). `POST /auth/reissue` → Refresh 검증 후 Access 재발급 | 검증: `LoginIntegrationTest`·`ReissueIntegrationTest` green, Redis TTL 실측값 14일 |
| 0.8 | D2 오전 | Security 인가 경계 | JWT 인증 필터, `/auth/**`·Swagger·Actuator 허용, 나머지 인증 필수 | 검증: 미인증 요청 401, 인증 요청 통과 (통합 테스트에 케이스 추가) |
| 0.9 | D2 오후 ~ D3 오전 | 사용자 조회 + 팔로우/언팔로우 | `GET /users/{userId}`, `POST/DELETE /users/{userId}/follow`. 팔로우와 `UPDATE users SET follower_count = follower_count ± 1`을 같은 트랜잭션(§4.4 패턴). 자기 자신 대상 follow/unfollow 요청은 400(시스템의 self-follow 행 보호). `is_influencer` 승격 로직은 **구현하지 않음** — Phase 3(→ [./60-phase-3-async-hybrid.md](./60-phase-3-async-hybrid.md)) | 검증: `FollowIntegrationTest` green — 카운터 증감·중복 거부·self 대상 400 |
| 0.10 | D3 오후 | 팔로워/팔로잉 목록 | `GET /users/{userId}/followers`·`/followings` 커서 페이지네이션. **`follower_id != followee_id` 조건으로 self-follow 행 제외**(§4.3의 "대가" — 각 한 줄) | 검증: `FollowListIntegrationTest` green — 목록에 본인 부재, 응답 형식 §6 준수 |
| 0.11 | D4 | 게시글 CRUD(수정 제외) + 좋아요 | `POST /posts`(Snowflake ID), `GET /posts/{postId}`, `DELETE /posts/{postId}`(**soft delete** — `is_deleted = true`, 삭제 글 조회 404), `GET /users/{userId}/posts`(단일 테이블 커서 쿼리). 좋아요: likes 삽입 + `like_count` 원자 UPDATE 같은 트랜잭션(§4.4의 쓰기 경로 — `post:{postId}` 캐시 관련 부분은 Phase 2a → [./30-phase-2a-push.md](./30-phase-2a-push.md)). `PATCH /posts/{postId}`는 **만들지 않는다**(§3 의도적 제외) | 검증: `PostIntegrationTest`·`AuthorPostsIntegrationTest`·`LikeIntegrationTest` green |
| 0.12 | D5 오전 | `compose.bench.yml` / `compose.async.yml` 오버레이 | bench: dev + Prometheus 0.4G + Grafana 0.3G (합 4.2G). async: MySQL 2G + Redis 1.5G + Kafka(KRaft) 1.2G, **모니터링 제외** (합 4.7G) — §3 표 그대로. Prometheus는 호스트 앱의 `/actuator/prometheus`를 타겟으로 하는 최소 설정만(대시보드 구성은 Phase 4′ → [./50-phase-4-stabilize-document.md](./50-phase-4-stabilize-document.md)) | 검증: `docker compose -f compose.yml -f compose.bench.yml up` 기동 + Prometheus targets UP, async 오버레이도 기동 확인(arm64 이미지 호환 조기 검증), `docker stats` 상한 일치 |
| 0.13 | D5 오후 | Testcontainers 환경 정비 | MySQL+Redis 싱글턴 컨테이너 패턴으로 스위트 전체 재사용, 테스트 컨테이너에도 Flyway 적용(스키마 경로 단일화). dummy 소스셋 출력물을 test 클래스패스에 추가해 `of()` 비트 배치 = `nextId()` 비트 배치 일치 테스트 | 검증: `./gradlew test` 전체 green, 스위트 소요 시간 기록(CI 예산 판단용) |
| 0.14 | D6 오전 | GitHub Actions CI | ubuntu-latest + Temurin 21 + Gradle 캐시. `./gradlew test`(Testcontainers는 러너 기본 Docker 사용). **bootJar 내 dummy 클래스 부재 검사 스텝** 포함(C4 자동화) | 검증: push 트리거 워크플로 green, README에 상태 뱃지 |
| 0.15 | D6 오후 | Phase 0 마감 점검 | §1의 완료 조건 C1~C7 전수 점검, 통합 테스트 통과 목록 확정, 미비점은 W2 버퍼일에 해소. Phase 1 인계 사항 기록(본문 5절 산출물 기준) | 검증: C1~C7 체크리스트 전부 통과, 마감 커밋 push |

---

## 4. 기술 상세

이 Phase가 구현하는 마스터 결정의 실행 관점 요약이다. 근거 전문은 마스터 해당 절을 본다.

### 4.1 패키지 구조 — 모놀리식 + 명확한 모듈 경계 (§3 "의도적으로 제외: MSA")

MSA를 하지 않는 대신 **도메인별 패키지가 곧 모듈 경계**다. 제안 트리:

```
src/main/java/com/timeline
├── common
│   ├── config/          # SecurityConfig, RedisConfig, JpaConfig, SwaggerConfig
│   ├── error/           # GlobalExceptionHandler, ErrorCode, ErrorResponse
│   ├── api/             # 커서 페이지네이션 공통 응답 (data / nextCursor / hasNext, §6)
│   └── snowflake/       # SnowflakeIdGenerator — nextId()만 public (§4.2)
├── auth/                # AuthController, AuthService, JwtProvider, RefreshTokenRepository(Redis)
├── user/                # User 엔티티, UserRepository, UserController, UserService
├── follow/              # Follow 엔티티, FollowRepository, FollowController, FollowService
├── post/                # Post 엔티티, PostRepository, PostController, PostService (soft delete)
├── like/                # Like 엔티티, LikeRepository, LikeController, LikeService
└── timeline/            # Phase 0에서는 경계만 잡는 빈 패키지.
                         # Phase 1: Pull 조회 / Phase 2a: Push 경로가 여기 들어온다
```

경계 규칙 (2개면 충분하다):

1. 도메인 패키지 간 참조는 Service 계층끼리만. Repository·엔티티를 타 도메인이 직접 참조하지 않는다.
2. `timeline`은 조회 전용 도메인으로, `post`·`follow`를 읽기만 한다. 역방향 참조 금지 —
   Phase 2a에서 fan-out이 들어와도 이 경계가 유지되는지가 "모듈 경계 명확한 모놀리식"(§3)의 검증 지점이다.

`src/dummy/java/com/timeline/common/snowflake/` — main과 **같은 패키지 경로**의 별도 소스셋 (아래 4.6절).
Phase 1의 더미 데이터 생성기도 이 소스셋에 들어간다.

### 4.2 Docker Compose 오버레이 3종 (§3)

메모리 수치는 §3 표 그대로다. 임의 조정하지 않는다.

| 파일 | 프로파일 | 구성 | 합계 |
|---|---|---|---|
| `compose.yml` | dev | MySQL 2G + Redis 1.5G | 3.5G |
| `+ compose.bench.yml` | bench | dev + Prometheus 0.4G + Grafana 0.3G | 4.2G |
| `+ compose.async.yml` | async | MySQL 2G + Redis 1.5G + Kafka(KRaft) 1.2G, 모니터링 off | 4.7G |

- 실행: `docker compose up`(dev) / `docker compose -f compose.yml -f compose.bench.yml up`(bench) / async 동일 패턴.
- Redis는 `maxmemory 1gb` + `allkeys-lru`(§5-5) — 컨테이너 한도(1.5G)와 maxmemory(1G)는 다른 값이다. §9.7 기록 서식과 일치시킨다.
- 애플리케이션은 컨테이너에 넣지 않는다. **호스트에서 `-Xmx1g`로 직접 실행**(§3). k6도 호스트 네이티브(Phase 1).
- `innodb_buffer_pool_size = 1G`는 **Phase 1 체크박스**다(§8 Phase 1). Phase 0에서는 건드리지 않고 인계 항목으로 남긴다.
- Kafka는 Phase 3(W13~)까지 사용하지 않지만, 컨테이너 정의와 기동 확인만 지금 해 둔다 —
  arm64 이미지 호환 문제를 Phase 3에서 처음 발견하는 상황을 막기 위해서다.

### 4.3 V1 마이그레이션 + `ddl-auto: validate` (§4.1, §3)

Flyway를 쓰는 이유는 데이터 보호 + **인덱스 변경 이력 자체가 실패한 시도의 기록**이 되기 때문이다(§3).
스키마의 유일한 관리자는 Flyway이고 Hibernate는 검증만 한다(`validate`).

`V1__init_schema.sql`은 §4.1 ERD 그대로. 실행 관점 요점만 적으면:

- `users` — `follower_count INT DEFAULT 0`(비정규화, self-follow 제외), `is_influencer BOOLEAN DEFAULT false`,
  **`influencer_since DATETIME NULL`**(승격 시각 = Hybrid 머지 경계, §7.2). 컬럼은 지금 만들고 로직은 Phase 3.
- `follows` — `UNIQUE (follower_id, followee_id)`(중복 방지 + Pull 조회 진입 인덱스 겸용),
  `INDEX (followee_id, follower_id)`(fan-out 대상 커버링, Phase 2a에서 사용).
- `posts` — `id BIGINT PK`(Snowflake, AUTO_INCREMENT 아님), `content VARCHAR(500)`,
  `like_count INT DEFAULT 0`, `is_deleted BOOLEAN DEFAULT false`.
  **조회용 보조 인덱스는 V1에 넣지 않는다**(§4.1 주석) — `(author_id, id DESC)`·`(author_id, is_deleted, id DESC)`는
  후보로 두고 Phase 1의 실측 비교(P1-13)로 채택 인덱스를 최초 도입한다.
- `likes` — `UNIQUE (post_id, user_id)`.

M0("인덱스 없음", §9.2)와의 관계: **V1은 posts 조회용 보조 인덱스를 포함하지 않는다**(§4.1 주석).
UNIQUE·FK 제약과 follows의 `(followee_id, follower_id)` 인덱스는 유지한다. 채택 인덱스는
**Phase 1의 후보 실측 비교(P1-13)** 마이그레이션으로 최초 도입한다
(→ [./20-phase-1-baseline.md](./20-phase-1-baseline.md)). Phase 0에서 이를 선반영하지 않는다.

### 4.4 JWT 인증 (§5, §6)

- `signup / login / reissue` 3개 엔드포인트(§6). 소셜 로그인 없음(§3 의도적 제외).
- Refresh Token은 Redis `refresh:{userId}` String, **TTL 14일**(§5). 사용자당 1개(키가 userId 단위) —
  재로그인 시 덮어쓴다.
- Access 토큰 TTL은 **30분 — 마스터 부록 B에 등재된 확정 결정**(측정 서사와 무관한 값. §9.3이 JWT를 `setup()`에서
  사전 발급해 재사용하므로 부하 테스트 중 만료되지 않을 길이면 충분하고, k6 시나리오는 5분이다).
- 비밀번호는 BCrypt. §9.3의 "매 요청 로그인하면 BCrypt가 병목" 경고는 측정 설계의 문제이지
  Phase 0 구현의 문제가 아니다 — 여기서는 표준 구현만 한다.

### 4.5 가입 시 self-follow 행 삽입 (§4.3)

Pull(JOIN)과 Push(직접 ZADD)의 **결과 집합을 같게 만들어 Phase 간 비교를 성립시키는 장치**다.
UNION ALL 대안은 임시 테이블로 실행 계획 분석을 오염시켜 버렸다(§4.3). 구현 체크 3가지:

1. 가입 트랜잭션 안에서 `follows(follower_id = followee_id)` 1건 삽입. 이때 `follower_count` 증가 없음.
2. 팔로워/팔로잉 목록 쿼리에 `follower_id != followee_id` 조건 각 한 줄(§4.3의 "대가").
3. 팔로우/언팔로우 API로 self 행을 만들거나 지울 수 없게 400 처리 — self 행은 시스템 불변식이다.

이 행이 실제로 일을 하는 순간은 Phase 1의 Pull 쿼리("내 글도 함께 조회된다", §8 Phase 1)와
Phase 2b의 fan-out 대상 제외(§7.7)다. Phase 0에서는 삽입과 제외 처리까지만 책임진다.

### 4.6 Snowflake 생성기 + 더미 소스셋 격리 (§4.2)

- 비트 배치: `[1bit 미사용][41bit 타임스탬프][10bit 노드ID][12bit 시퀀스]`. 정렬 = 시간순 정렬이
  전제이므로 **프로덕션 API는 `nextId()` 하나뿐이다.** 백데이팅 API가 main에 있으면 그게 ID 위조 경로다(§4.2).
- 커스텀 epoch는 2025-01-01T00:00:00Z로 고정한다(**마스터 부록 B에 등재된 확정 결정** — Phase 1 더미의 최대 백데이팅
  180일(§8 Phase 1)을 여유 있게 커버하는 값. 한 번 정하면 불변).
- 단일 인스턴스 운영이므로 프로덕션 nodeId는 0 고정(부록 B). 노드ID 0~15 병렬 생성은 더미 생성기(Phase 1)의 몫.

Gradle 소스셋 구성 (Kotlin DSL):

```kotlin
// build.gradle.kts
sourceSets {
    create("dummy") {
        java.srcDir("src/dummy/java")
        compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}
// bootJar는 main 소스셋만 패키징한다 → dummy 클래스는 배포 산출물에 물리적으로 부재
```

격리 방식: main의 `SnowflakeIdGenerator`는 비트 시프트 상수(epoch, 시프트 폭)를 **package-private**으로
두고, `of(epochMilli, nodeId, sequence)` 팩토리는 `src/dummy/java`의 **같은 패키지** 클래스
`SnowflakeIdFactory`로 구현한다(package-private 접근은 소스셋이 달라도 같은 패키지면 컴파일된다).
결과:

- main 코드가 `of(...)`를 호출하면 **컴파일 에러** (클래스가 main 클래스패스에 없음)
- `bootJar`에 `SnowflakeIdFactory`가 **물리적으로 없음** — CI에서 `unzip -l`로 자동 검사(0.14)
- test 소스셋에는 dummy 출력물을 추가해 두 생성 경로의 비트 배치 일치를 테스트(0.13)

### 4.7 게시글·팔로우·좋아요 구현 원칙 (§4.4, §6)

- **수정 API 없음**(§3). 삭제는 soft delete — `is_deleted = true`만 바꾼다. 이 플래그 하나가 Phase 2a에서
  "흩어진 모든 타임라인에 즉시 반영"(§5-1)되는 장치이므로 물리 삭제를 만들지 않는다.
- 커서 페이지네이션만 쓴다. OFFSET 금지(§6). 응답 형식 `{data, nextCursor, hasNext}` 공통화.
- 카운터(`follower_count`, `like_count`)는 행 변경과 **같은 트랜잭션의 원자 UPDATE**(§4.4).
  카운터의 진실은 DB다 — Redis 카운터 키는 만들지 않는다(§7.4, §5의 "설계에서 뺀 키").
- 좋아요의 읽기 경로(`post:{postId}` 캐시 경유, 1시간 stale 허용, 본인 `DEL` 후 즉시 반영)는
  Redis 캐시가 생기는 Phase 2a에서 구현한다. Phase 0은 DB 쓰기 절반만 구현한다.
- `GET /timeline`은 구현하지 않는다 — Pull 쿼리 구현·측정이 Phase 1의 본문이다(§8 Phase 1).

### 4.8 Testcontainers + CI (§3)

- 실제 MySQL 8.0 + Redis 7 컨테이너로 통합 테스트한다(H2·embedded 대체 금지 — §3의 선택 이유가
  "실제 MySQL/Redis로 통합 테스트"다). 싱글턴 컨테이너 패턴으로 스위트당 1회만 기동.
- 테스트 스키마도 Flyway가 만든다. 스키마 정의 경로를 하나로 유지해야 `validate`가 의미를 갖는다.
- CI(GitHub Actions)는 push/PR마다 `./gradlew test` + bootJar 격리 검사(C4).
  Phase 2b에서 Pull/Push 동등성 검증(§9.4)이 이 CI에 편입되므로, 지금 만드는 파이프라인이 그 자리다.
- 테스트 커버리지 70%(§2)는 프로젝트 종료 시점 목표다. Phase 0에서는 게이트로 걸지 않고
  측정만 시작한다(Jacoco 리포트 생성).

---

## 5. 산출물

경로는 저장소 루트 기준.

| 분류 | 경로 | 내용 |
|---|---|---|
| 빌드 | `build.gradle.kts`, `settings.gradle.kts` | Java 21 / Boot 3.x / dummy 소스셋 정의 |
| 인프라 | `compose.yml`, `compose.bench.yml`, `compose.async.yml` | §3 메모리 할당 그대로 |
| 인프라 | `docker/prometheus/prometheus.yml` | bench용 최소 스크레이프 설정 (호스트 앱 타겟) |
| 스키마 | `src/main/resources/db/migration/V1__init_schema.sql` | users/follows/posts/likes — posts 조회용 보조 인덱스 미포함(§4.1 주석) |
| 설정 | `src/main/resources/application.yml` | `ddl-auto: validate`, Flyway, Redis, JWT 설정 |
| 코드 | `src/main/java/com/timeline/...` | 본문 4.1절 패키지 트리 (auth/user/follow/post/like + common) |
| 코드 | `src/main/java/com/timeline/common/snowflake/SnowflakeIdGenerator.java` | `nextId()`만 public |
| 코드 | `src/dummy/java/com/timeline/common/snowflake/SnowflakeIdFactory.java` | `of(epochMilli, nodeId, sequence)` — 배포 산출물에서 격리 |
| 테스트 | `src/test/java/com/timeline/...` | §1의 통합 테스트 목록 10종 |
| CI | `.github/workflows/ci.yml` | test + bootJar 격리 검사 |
| 문서 | `README.md` | §10 목차 뼈대 + CI 뱃지 |
| 문서 | (자동 생성) `/swagger-ui/index.html` | springdoc — 커밋 산출물 아님, 실행 시 확인 |

**Phase 1로의 인계 사항** (→ [./20-phase-1-baseline.md](./20-phase-1-baseline.md)):

- `innodb_buffer_pool_size = 1G` 설정 (§8 Phase 1 체크박스)
- V1은 posts 보조 인덱스 미포함 — 채택 인덱스는 Phase 1 실측 비교(P1-13)로 최초 도입 (본문 4.3절)
- dummy 소스셋에 더미 데이터 생성기 추가 (JDBC Batch, 노드ID 0~15 병렬 — §8 Phase 1)
- `GET /timeline` Pull 구현 + k6 시나리오

---

## 6. 리스크와 대응

Phase 0 고유의 것만 적는다. 프로젝트 전역 리스크는 마스터 §12.

| 리스크 | 징후 | 대응 |
|---|---|---|
| **W1 6단계 미완주** — 부록 A 목표 미달 시 이후 Phase 전체가 밀린다 | D4 시점에 0.4(Flyway)가 안 끝나 있음 | 0.6이 W1 유일한 데드라인. 밀리면 W2에서 async 오버레이(0.12 후반)를 최후순위로 미룬다 — async는 Phase 3(W13)까지 미사용이라 유일하게 미룰 수 있다. **bench는 Phase 1 측정에 필요하므로 못 미룬다** |
| Apple Silicon 이미지 비호환 (MySQL 8.0 / Kafka KRaft arm64) | 컨테이너 기동 실패, qemu 에뮬레이션으로 느려짐 | 0.3/0.12에서 3종 프로파일을 **전부 기동해보는 것 자체가 검증**. arm64 네이티브 태그 확인, 비호환 시 이미지 태그만 교체(스택 변경은 하지 않는다) |
| Testcontainers가 CI에서 느리거나 불안정 | CI 소요가 로컬 대비 수 배 | 싱글턴 컨테이너 + Gradle 캐시. 0.13에서 스위트 시간을 기록해 CI 예산을 수치로 관리. Phase 2b에서 동등성 검증(§9.4)이 CI에 추가될 것을 감안해 지금 5분 이내 유지 |
| 소스셋 격리가 조용히 깨짐 (리팩터링 중 팩토리가 main으로 이동 등) | 코드 리뷰로만 잡히는 상태 | C4를 **CI 단계로 자동화**(0.14) — bootJar 내 dummy 클래스 검출 시 빌드 실패 |
| 스코프 크리프 — "기능이 다 되는 김에" 성능 손대기 | Phase 0 중 인덱스 추가·캐시 도입·쿼리 튜닝 커밋 | M0가 최악 baseline이어야 한다(§9.2). Phase 0에서 성능 커밋 금지를 규칙으로 고정. 기능 추가 금지 목록(§3: 댓글·해시태그·검색·DM·차단·수정)도 동일 |
| V1 확정 후 스키마 흔들림 | 엔티티 수정 때마다 V1을 고치고 싶어짐 | V1은 §4.1 그대로 확정하고 이후 변경은 **새 마이그레이션 파일**로만 — 이력 자체가 산출물이다(§3). V1 소급 수정 금지 |

---

## 7. 마스터 체크박스 매핑

마스터 §8 Phase 0의 체크박스 9개 ↔ 이 문서 작업 번호. 누락 없음을 확인한다.

| 마스터 §8 Phase 0 체크박스 | 이 문서 작업 | 검증 위치 |
|---|---|---|
| 프로젝트 세팅, 패키지 구조 설계 (도메인별 분리) | 0.1, 0.2 | 본문 4.1절 트리, C5 |
| Docker Compose 오버레이 3종 (`dev`/`bench`/`async`) — §3 | 0.3, 0.12 | C3 |
| Flyway 마이그레이션 · `ddl-auto: validate` | 0.4 | C2, `SchemaValidationTest` |
| 회원가입/로그인 (JWT + `refresh:{userId}`) | 0.6, 0.7, 0.8 | `Signup/Login/ReissueIntegrationTest` |
| **가입 시 self-follow 행 삽입** (→ §4.3) | 0.6 (제외 처리는 0.9, 0.10) | `SignupIntegrationTest`, `FollowListIntegrationTest` |
| 게시글 CRUD(수정 제외), 팔로우 기능 | 0.9, 0.10, 0.11 | `Follow/FollowList/Post/AuthorPosts/LikeIntegrationTest` |
| Snowflake ID 생성기 + 팩토리 더미 소스셋 격리 (→ §4.2) | 0.5 | `SnowflakeIdTest`, C4 |
| Testcontainers 통합 테스트 환경 | 0.6(최초 1개), 0.13(정비) | C1 |
| GitHub Actions CI | 0.14 | C6 |

부록 A(첫 주 6단계) ↔ W1 작업은 3절 W1 표의 "부록 A" 열에서 1:1로 매핑했다 (A-1~A-6 = 0.1~0.6).

**완료 조건 대조**: 마스터의 "기능은 다 되지만 데이터가 없고 성능 개념도 없는 상태"
= 이 문서 C1~C7. 특히 C7이 "데이터 없음·성능 개념 없음"을 명시적으로 지킨다.

---

## 8. 마감 점검 결과 (0.15 · 2026-08-25)

C1~C7 전수 실측 통과. 세부:

| # | 결과 | 실측 근거 |
|---|---|---|
| C1 | ✅ | 통합 테스트 **58개** 전부 green (`./gradlew clean test` 46초, 콜드 컨테이너 포함). GitHub Actions 첫 run green |
| C2 | ✅ | 빈 DB → Flyway V1 → `ddl-auto: validate` 부팅 성공 (0.4에서 엔티티가 DB에 없는 컬럼을 가리키게 하는 부정 실험까지 수행). `TimelineApplicationTests`가 §1 목록의 `SchemaValidationTest` 역할을 겸한다 |
| C3 | ✅ | 3종 프로파일 전부 기동 실측 — dev(MySQL 2G/Redis 1.5G), bench(+Prometheus 0.4G·Grafana 0.3G, 타겟 UP), async(+Kafka 1.2G healthy, arm64 네이티브). `maxmemory`=1073741824, policy=allkeys-lru |
| C4 | ✅ | bootJar에 `SnowflakeIdFactory` 부재 (`unzip -l` 실측) + CI 스텝 자동화 (동적 대조 + 이름 안전망, 주입 시뮬레이션으로 검출 확인) |
| C5 | ✅ | `/v3/api-docs`에 §6 Phase 0 범위 12개 엔드포인트 노출 (auth 3 · users/follow 5 · posts/likes 4). `GET /timeline` 부재 = 정상 |
| C6 | ✅ | push 트리거 워크플로 green run + README 뱃지 |
| C7 | ✅ | 더미 데이터 생성 코드 없음(dummy 소스셋엔 `SnowflakeIdFactory`뿐) · posts 조회용 보조 인덱스 없음(`SHOW INDEX` 실측, FK 부산물 `fk_posts_author`만 — 부록 B 해석 지침) · Redis 캐시 조회 경로 없음(refresh 토큰만) · `PATCH /posts` 없음 |

§1 통합 테스트 목록 10종 ↔ 실제 클래스 매핑: Signup(3)·Login(4)·Reissue(6)·Follow(9)·FollowList(6)·Post(8)·AuthorPosts(4)·Like(8)·SchemaValidation(=`TimelineApplicationTests` 1)·SnowflakeId(5) + 추가 `SecurityBoundaryIntegrationTest`(4).

**0.13 기록** — 스위트 소요: 로컬 `clean test` 46초(콜드), warm 컨텍스트 재실행 시 ~20초. CI 전체 job 예산 15분 중 실측 수 분 내 완료. Phase 2b 동등성 검증이 얹혀도 5분 예산에 여유.

**Phase 1 인계 사항** (본문 5절과 동일, 재확인):
- `innodb_buffer_pool_size = 1G` 설정 (M0 측정 전 적용 — §8 Phase 1 체크박스)
- posts 채택 인덱스는 P1-13 실측 비교로 최초 도입 (후보 2종, V1 주석)
- dummy 소스셋에 더미 데이터 생성기 추가 (JDBC Batch, 노드ID 0~15 병렬) — CI의 C4 동적 대조가 자동 커버
- `GET /timeline` Pull 구현 + k6 시나리오 (open model)
