# CLAUDE.md — 타임라인 서비스

## 이 프로젝트는 무엇인가

트위터형 팔로우 기반 타임라인 **백엔드 API** (이직 포트폴리오).
목적은 기능 구현이 아니라 **성능 개선 과정의 증명**이다 — 같은 기능을
Pull → Push → Hybrid로 3번 구현하며 각 단계의 한계를 측정(M0~M4)으로 남긴다.
실질적 산출물은 코드가 아니라 **README와 성능 리포트**다.

## 문서 체계 — 어디를 보고 일하는가

| 문서 | 역할 |
|---|---|
| `timeline-project.md` | **유일한 진실(SoT).** 모든 설계 결정과 근거. 구현이 이 문서와 다르면 구현이 틀린 것 |
| `docs/roadmap/00-overview.md` | 일정·마일스톤·진행 현황판 |
| `docs/roadmap/{10..60}-*.md` | Phase별 실행 계획 (일 단위 작업 + 검증 방법) |

**규칙**
- 확정된 결정을 재론하지 않는다. 결정 목록은 마스터 §7과 부록 B에 있다.
- 마스터가 답하지 않는 새 결정이 필요하면 → 결정하고 **부록 B에 등재**한 뒤 진행한다.
- 작업 완료 기준은 로드맵 문서의 해당 작업 "검증:" 열이다. 검증 없이 완료 선언 금지.

## 절대 규칙 (스코프 가드)

- **기능 추가 금지**: 댓글, 해시태그, 검색, DM, 차단, 게시글 수정, 소셜 로그인. 성능 외 기능은 늘리지 않는다.
- **도입 금지**: QueryDSL, MSA, Kubernetes, 프론트엔드, Caffeine 2계층 캐시 (제외 사유는 마스터 §3).
- **측정 없이 다음 Phase로 넘어가지 않는다.** M0~M4가 체크포인트다.
- 측정 수치는 지어내지 않는다. 실측 전에는 리포트에 빈칸으로 둔다.

## 핵심 확정 수치 (구현 시 자주 필요한 것)

| 항목 | 값 |
|---|---|
| 더미 데이터 | 사용자 10만 / 팔로우 300만 / 게시글 300만 (본문 평균 80B) |
| Hybrid 임계치 | **5,000** (비교: 500/5,000/20,000) · 단방향 승격 + `influencer_since` |
| 타임라인 상한 | Sorted Set **500개** (`ZREMRANGEBYRANK 0 -501`) |
| Redis | maxmemory **1GB** + allkeys-lru · `post:{id}` TTL 1시간 · tombstone 60초 |
| 폴백 보호 | 세마포어 **20** · `SET NX` 락 10초 · 실패 시 503+Retry-After · 폴백은 최근 100개만 |
| 페이지 | 25개 조회 → 삭제 필터 → 20개 반환 · 재조회 상한 **3회** |
| 백필 | N=**20**, 비동기, 인플루언서 제외 |
| 언팔로우 | **C(방치)** — 제거하지 않는다 |
| 카운터 SoT | **DB** (원자적 UPDATE, 타인에게 1시간 stale 허용) |
| Kafka | 파티션 키 **없음** + 팔로워 500명 청크 |
| 읽기:쓰기 | 50:1 (k6 혼합: 조회 98% / 작성 2%) |
| Snowflake | epoch 2025-01-01T00:00:00Z · 프로덕션 nodeId 0 · 백데이팅 팩토리는 더미 소스셋 격리 |
| JWT | Access 30분 / Refresh 14일 (`refresh:{userId}`) |

## 구현 규칙

- **Java 21 + Spring Boot 3.x + Spring Data JPA(`@Query`)**. 모놀리식, 도메인별 패키지 분리.
- **스키마는 Flyway로만 변경** (`ddl-auto: validate`). 인덱스 추가/되돌림도 마이그레이션으로 — 이력 자체가 포트폴리오다.
  - V1에는 posts 조회용 보조 인덱스를 넣지 않는다 (인덱스는 M0 분석의 산출물, 마스터 §4.1 주석).
- **가입 시 self-follow 행 삽입.** 팔로워/팔로잉 목록 API와 `follower_count`에서는 제외. 자기 자신 follow/unfollow API는 400.
- 게시글 삭제는 **soft delete** (`is_deleted`). 하드 삭제 없음.
- 타임라인 캐시에는 **post_id만** 저장 (본문 복사 금지).
- Push를 만들어도 **Pull 폴백 경로는 항상 유지**한다.
- 통합 테스트는 **Testcontainers** (실제 MySQL/Redis). **Pull==Push 동등성 테스트는 CI 상시 실행** (Phase 2b부터).
- Docker Compose는 프로파일 3종(`dev`/`bench`/`async`)을 지키고, 컨테이너 메모리 한도를 임의로 올리지 않는다 — **호스트가 M1 8GB**라는 게 전체 설계의 전제다.

## 측정 규칙 (마스터 §9)

- k6는 **open model만** (`ramping-arrival-rate`/`constant-arrival-rate`). `ramping-vus` 금지.
- `dropped_iterations > 0`이면 그 측정은 무효 — 폐기하고 재실행.
- 3회 반복 중앙값 + 편차 병기. 측정 전 스냅샷 복원. cold/warm 상태 명기.
- JWT는 k6 `setup()`에서 사전 발급 (매 요청 로그인 시 BCrypt가 병목이 된다).
- 결과는 `docs/perf/{측정ID}.md` (§9.7 템플릿) + 원시 JSON `docs/perf/raw/{측정ID}/run{n}.json`.

## 커밋 · 태그

- Phase 태그: `v1-pull`, `v2-push`, `v3-hybrid` — 부여 시점은 Phase 4′ 로드맵(4-13)을 따른다.
- 커밋 메시지는 한국어, 변경의 "왜"를 담는다 (예: `M0 분석 결과에 따라 (author_id, id DESC) 인덱스 도입`).
