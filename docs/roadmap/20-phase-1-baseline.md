# Phase 1 — 더미 데이터 & 첫 측정 (W3~W5 · 2026-09-07 ~ 2026-09-27 · 선행: Phase 0)

> 마스터: [../../timeline-project.md](../../timeline-project.md) §8 Phase 1, §9
> 마스터가 **"이 단계가 프로젝트의 성패를 가른다"** 고 못박은 단계다.
> 데이터 없이는 아무 문제도 발생하지 않고, M0/M1 없이는 이후 모든 개선 주장이 근거를 잃는다.

| 항목 | 내용 |
|---|---|
| 기간 | W3~W5 · 2026-09-07(월) ~ 2026-09-27(일) · 3주 |
| 선행 Phase | [Phase 0 — 기반 구축](./10-phase-0-foundation.md) (W1~W2) |
| 다음 Phase | [Phase 2a — Push 전환](./30-phase-2a-push.md) (W6~W7) |
| 실행 프로파일 | 더미 생성: `dev` / 측정: `bench` (마스터 §3) |
| 로드맵 전체 | [00-overview.md](./00-overview.md) |

---

## 1. 목표와 완료 조건

### 목표

이 Phase의 성공은 낮은 지연이 아니다. **p99가 수 초 단위로 나오는 것이 정상이다**(§8 Phase 1).
목표는 다음 세 가지를 "검증 가능한 상태"로 만드는 것이다.

1. **마스터 분포표 그대로의 데이터셋** — 사용자 10만 / 팔로우 관계 300만 / 게시글 300만(본문 평균 80B)
2. **신뢰할 수 있는 최악 baseline(M0)** — open model, 3회 반복, `dropped_iterations == 0`
3. **"인덱스만으로는 한계가 있었다"의 유일한 증거(M1)** — §9.2가 규정한 이 숫자가 없으면
   §10의 "실패한 시도" 서사가 근거 없는 문장이 된다

### 완료 조건 (전부 충족해야 Phase 2a 진입)

- [ ] **데이터 검증 쿼리 전부 통과**: `users` 100,000 / `follows` 실관계 3,000,000(+self-follow 100,000행) /
      `posts` 3,000,000 · 팔로워 계층(S/A/B/C)·팔로잉 코호트·시간 분포 검증 쿼리 일치 ·
      `follower_count`가 실제 집계(self-follow 제외)와 일치
- [ ] **Pull 타임라인 통합 테스트 green**: 내 글 포함(self-follow) · `is_deleted` 필터 · 커서 페이지네이션 연속성
- [ ] **M0/M1 유효 측정 완료**: 각각 코호트 3종(`light`/`normal`/`heavy`) × 3회 반복,
      모든 유효 런에서 `dropped_iterations == 0`, 중앙값 + 최대-최소 편차 기록
- [ ] **`docs/perf/m0.md` / `docs/perf/m1.md` 커밋** (§9.7 템플릿) + **k6 원시 결과(JSON summary) 커밋**
- [ ] `m1.md`에 **M0 대비 개선 폭**과 **남은 병목**이 `EXPLAIN ANALYZE` 실측(행수·filesort 여부)으로 서술됨
      → Phase 2a의 동기 문장이 여기서 나온다
- [ ] 측정 중 **buffer pool 히트율 ≥ 95%** 확인 — 미달 시 게시글 200만 하향 트리거 발동 + 리포트에 기록(§8, §12)
- [ ] **단일 명령 재현**: `make seed` / `make bench-m0` / `make bench-m1` (§9.6)
- [ ] 코호트 파일 `k6/data/cohorts.json`이 존재하고, k6 `setup()`이 이 파일로 JWT를 사전 발급함

---

## 2. 선행 조건

Phase 0([./10-phase-0-foundation.md](./10-phase-0-foundation.md))의 산출물 중 이 Phase가 직접 소비하는 것:

| Phase 0 산출물 | Phase 1에서의 용도 | 시작 시 점검 방법 |
|---|---|---|
| JWT 회원가입/로그인 API | k6 `setup()`의 토큰 사전 발급(§9.3) | 더미 사용자 1명으로 로그인 → 200 + 토큰 |
| 가입 시 self-follow 삽입 로직 (§4.3) | 생성기가 **같은 불변식**을 유지해야 Pull 결과에 내 글이 포함됨 | 신규 가입 후 `follows`에 self 행 1건 확인 |
| Snowflake 생성기 + **타임스탬프 주입형 팩토리 `of(epochMilli, nodeId, sequence)`** (§4.2) | 게시글 300만 건 백데이팅 생성. 팩토리는 더미 소스셋에만 존재해야 함 | 프로덕션 소스셋에서 팩토리 참조 불가(컴파일 경계) 확인 |
| Flyway 초기 스키마 — **`posts`에 조회용 보조 인덱스가 없는 상태** | M0("인덱스 없음")의 성립 전제 | Phase 0 V1은 posts 조회용 보조 인덱스 미포함으로 확정(마스터 §4.1 주석) — 시작 시 `SHOW INDEX FROM posts`로 이중 확인만 |
| Docker Compose 오버레이 `dev`/`bench` (§3) | 생성은 `dev`(3.5G), 측정은 `bench`(4.2G) | 두 프로파일 기동 확인 |
| 게시글 CRUD·팔로우 API | Pull 타임라인이 얹힐 도메인 계층 | Phase 0 완료 조건 |
| Testcontainers 통합 테스트 + CI | Pull 구현 검증 | CI green |

> `follows`의 `UNIQUE (follower_id, followee_id)` / `INDEX (followee_id, follower_id)`는
> 스키마 제약·Phase 2 fan-out용으로 Phase 0 스키마에 이미 존재한다(§4.1).
> **M0의 "인덱스 없음"은 `posts` 쪽 조회용 보조 인덱스의 부재**를 뜻한다. → §4.4 참조

---

## 3. 작업 분해

주말(토·일)은 버퍼다. 적재 장기 실행(수 시간)은 야간·주말 시간대를 활용한다.

### W3 (09-07 ~ 09-13) — 더미 데이터 생성기

| 번호 | 일자 | 작업 | 상세 | 검증 방법 |
|---|---|---|---|---|
| P1-01 | 09-07(월) | 생성기 골격 + users 10만 적재 | 더미 소스셋(예: Gradle `datagen` 소스셋)에 JDBC 생성기 작성. `rewriteBatchedStatements=true`, autocommit off, 배치 1,000행 / 10,000행 커밋. 전 사용자 동일 BCrypt 해시 1개 재사용(평문은 k6와 공유), username은 `user_{n}` 규칙. 고정 RNG 시드 | 검증: `COUNT(*)=100,000` · 샘플 사용자로 로그인 API 200 응답 · JPA 미사용(생성기 의존성에 JDBC만) |
| P1-02 | 09-08(화) | 계층·코호트 배정 + follows 생성 알고리즘 | S 10 / A 100 / B 1,000 / C 98,890 배정, 코호트 heavy 1,000 / normal 2,000 / light 2,000 배정. followee 슬롯 풀(팔로워 목표 수만큼 복제 후 셔플)에서 follower별 팔로잉 쿼터만큼 추출, (follower, followee) 중복·self 충돌은 재추출/스왑 보정 | 검증: 1,000명 축소 입력 단위 테스트에서 코호트 팔로잉 수 정확 일치 + 계층 팔로워 합계 오차 ±0.1% 이내 |
| P1-03 | 09-09(수) | follows 300만 + self-follow 10만 적재, `follower_count` 갱신 | 실관계 3,000,000행 + 전 사용자 self-follow 100,000행(§4.3 불변식). 적재 후 `follower_count`를 집계 UPDATE(self-follow 제외, §4.1) | 검증: 계층별 팔로워 합계 쿼리(S=200,000 / A=500,000 / B=500,000 / C=1,800,000) · 코호트별 팔로잉 수(500/100/10) 정확 일치 · `follower_count` 샘플 100명 집계 대조 |
| P1-04 | 09-10(목) | posts 300만 적재 (타임스탬프 주입 Snowflake) | 16스레드, 스레드당 노드ID 0~15 고정(ms당 4,096 시퀀스 한계 회피). 시간 분포: 최근 7일 25% / 8~30일 35% / 31~180일 40%(버킷 내 균등). `created_at` = ID에 주입한 epochMilli. 본문 평균 80B, 작성자는 전 사용자 균등 랜덤(1인 평균 30건) | 검증: `COUNT(*)=3,000,000` · 구간별 비율 쿼리(25/35/40 ±1%p) · 샘플 1만 건에서 `ID>>22` 타임스탬프와 `created_at` 일치 · `AVG(LENGTH(content))` ≈ 80B |
| P1-05 | 09-11(금) | `make seed` 통합 + 코호트 export + buffer pool 설정 + 스냅샷 | (1) users→follows→posts 순서로 한 명령 실행, 소요 시간 로그 (2) `k6/data/cohorts.json` export(§4.2) (3) my.cnf `innodb_buffer_pool_size=1G` 적용(컨테이너 2G 한도 내) (4) `make db-snapshot SNAP=m0` / `make db-restore SNAP=m0` 구현(볼륨 tar 아카이브) | 검증: 초기화 후 `make seed` 전체 재실행 성공 · `SHOW VARIABLES`로 buffer pool 1G 확인 · 복원 후 3개 테이블 COUNT 일치 |

### W4 (09-14 ~ 09-20) — Pull 구현 + k6 + 측정 리허설

| 번호 | 일자 | 작업 | 상세 | 검증 방법 |
|---|---|---|---|---|
| P1-06 | 09-14(월) | Pull 타임라인 API 구현 | `GET /timeline?cursor=&size=20`. 본문 4.3절(마스터 §8 Phase 1)의 SQL 그대로(`is_deleted` 포함, LIMIT 25) → 앞 20건 반환, 21번째 행 존재로 `hasNext`, 20번째 postId가 `nextCursor`. 커서 미지정 시 최신부터 | 검증: Testcontainers 통합 테스트 — ① self-follow로 내 글 포함 ② 삭제 글 미노출 ③ 커서 연속 조회 시 중복·누락 없음 |
| P1-07 | 09-15(화) | 서버 사이드 계측 + 프로파일링 준비 | Micrometer `@Timed`로 DB 조회·직렬화 구간 분해(§9.3 — 클라이언트 지연 − 서버 내부 지연 = 큐잉 시간). `bench` 프로파일에서 MySQL slow log 활성화(`long_query_time=0.1`), JFR 녹화 스크립트 준비 | 검증: `/actuator/prometheus`에 구간 타이머 노출 · 수동 1회 조회 시 slow log 기록 확인 |
| P1-08 | 09-16(수) | k6 시나리오 작성 (open model 2종) | §9.1 구조 그대로: `saturation`(ramping-arrival-rate) + `slo`(constant-arrival-rate 1,000 RPS · 5분 · `startTime: '2m'`). `setup()`에서 시나리오 코호트 분량만 로그인(JWT 사전 발급, `http.batch`, `setupTimeout` 상향), VU는 토큰 재사용. thresholds에 `dropped_iterations: ['count==0']` 포함. JWT TTL이 런 길이(≤10분)를 넘는지 확인 | 검증: 스모크 런(rate 10, 1분) — `dropped_iterations==0`, 전 요청 200, 코호트 외 사용자 미사용(스크립트 검사) |
| P1-09 | 09-17(목) | 측정 파이프라인 리허설 | `make bench-m0` 1회 전체 실행: 스냅샷 복원 → `bench` 기동 → JVM 워밍업 2분 → saturation/slo → k6 CPU 샘플링 로그 → raw JSON 저장 → §9.7 템플릿에 수기 기입 | 검증: 리허설 결과로 m0.md 초안 표가 전부 채워짐 · k6 CPU 로그 파일 생성 확인 |
| P1-10 | 09-18(금) | 예비 측정 + buffer pool 히트율 판정 | heavy 코호트로 예비 saturation 런. `Innodb_buffer_pool_reads / read_requests`로 히트율 산출. **95% 미만이면 여기서 게시글 200만 하향을 결정**하고 W5 전에 재생성·재스냅샷(§4.6) | 검증: 히트율 수치 기록 · 미달 시 하향 결정 + 사유가 리포트 초안에 기재됨 |

### W5 (09-21 ~ 09-27) — M0 → 분석 → 인덱스 → M1

| 번호 | 일자 | 작업 | 상세 | 검증 방법 |
|---|---|---|---|---|
| P1-11 | 09-21(월) | **M0 본 측정** | snap-m0 복원 → 코호트 3종 × saturation 3회(M0용 하향 사다리, §4.4) + slo 1,000 RPS 시도. 매 반복 전 스냅샷 복원, cold/warm 구분 기록, k6 CPU 병행 기록 | 검증: 유효 런 전부 `dropped_iterations==0`(위반 런은 폐기·재실행) · 3회 중앙값+편차 산출 · slo 미성립 시 "주입 불가" 사실 자체를 결과로 기록 |
| P1-12 | 09-22(화) | `docs/perf/m0.md` 작성 + 병목 분석 | §9.7 템플릿. `EXPLAIN`(추정) vs `EXPLAIN ANALYZE`(실측) 대비, 실측 읽기 행수·filesort 여부·슬로우 쿼리 상위, JFR 플레임그래프로 병목 위치(JOIN? 정렬? I/O?) 확정. raw JSON을 `docs/perf/raw/m0/run{n}.json`(측정별 디렉토리 + `run{n}.json` 파일명 규칙)으로 커밋 | 검증: 템플릿의 모든 절이 채워짐 · "원인 분석"에 실측 행수 인용 · 원시 결과 커밋 확인 |
| P1-13 | 09-23(수) | 인덱스 후보 2종 실측 비교 + 채택 | Flyway로 후보 A `(author_id, id DESC)` / 후보 B `(author_id, is_deleted, id DESC)`(§4.1의 후보 규정)를 각각 적용: 스냅샷 복원 → 마이그레이션 → `EXPLAIN ANALYZE` + heavy 코호트 단축 부하 → 비교표. 채택안으로 `snap-m1` 생성, 탈락안은 되돌림 마이그레이션으로 이력 보존(§3 Flyway 취지) | 검증: 두 후보의 실행 계획·수치 비교표 존재 · Flyway 이력에 적용/되돌림 마이그레이션 잔존 · snap-m1 복원 시 채택 인덱스 확인 |
| P1-14 | 09-24(목) | **M1 본 측정** | snap-m1 복원 기준으로 M0과 동일 매트릭스(코호트 3종 × 3회 + slo). 프로토콜 동일(§4.5) | 검증: M0과 동일 게이트 · 코호트별 p50/p99/최대 RPS 기록 |
| P1-15 | 09-25(금) | `docs/perf/m1.md` 작성 + Phase 마감 | M0 대비 개선 폭(%) 표, **"인덱스만으로 부족했던 이유"**(개선 후에도 남는 병목을 실측으로), "남은 문제" 절에 Phase 2a 동기 명시. raw는 `docs/perf/raw/m1/run{n}.json`. 완료 조건 체크리스트 전수 점검 | 검증: §1 완료 조건 전 항목 체크 · m1.md에 M0 대비 표 + 남은 병목 + 다음 Phase 동기 존재 |

> 09-26(토)~09-27(일): 버퍼. 측정 재실행(무효 런 발생 시)·문서 보강에 사용.

---

## 4. 기술 상세

마스터 결정의 실행 관점 재서술이다. **결정 자체는 마스터가 진실이며 여기서 재론하지 않는다.**

### 4.1 더미 데이터 분포 — 마스터 §8 Phase 1 표 그대로

**팔로워 쪽 분포 (fan-out 부하를 결정)**

| 계층 | 인원 | 1인당 팔로워 | 소계 |
|---|---|---|---|
| S | 10 | 20,000 | 200,000 |
| A | 100 | 5,000 | 500,000 |
| B | 1,000 | 500 | 500,000 |
| C | 98,890 | ~18 | 1,800,000 |
| | | **합계** | **3,000,000** |

**팔로잉 쪽 분포 (Pull 조회 비용을 결정 — 측정 코호트의 원천)**

| 코호트 | 인원 | 팔로잉 수 | 소계 | 용도 |
|---|---|---|---|---|
| `heavy` | 1,000 | 500 | 500,000 | 팔로잉 수 영향 측정 |
| `normal` | 2,000 | 100 | 200,000 | 대표값 |
| `light` | 2,000 | 10 | 20,000 | 기본 성능 |
| 나머지 | 95,000 | ~24 | 2,280,000 | 배경 |

실행 시 지켜야 할 불변식:

- **생성 순서는 users → follows → posts.** follows가 users FK를, posts가 author FK를 참조한다
- 분포표의 300만은 **실팔로우 관계** 기준이다. §4.3의 self-follow 불변식을 생성기도 유지해야 하므로
  물리 행은 +100,000(전 사용자 self 행)이며, `follower_count`는 self-follow를 제외하고 집계한다(§4.1 ERD)
- 코호트 팔로잉 수(500/100/10)는 **정확 일치**가 게이트다 — "팔로잉 500명 조회" 시나리오의 전제이기 때문
- `is_influencer`/`influencer_since`는 기본값으로 둔다. Pull 경로는 이 컬럼을 읽지 않으며,
  승격 처리는 Phase 3([./60-phase-3-async-hybrid.md](./60-phase-3-async-hybrid.md)) 소관이다
- 부하 테스트는 **위 5,000명 코호트에서만 사용자를 뽑는다**(§8 Phase 1 — 랜덤 10만 추출은 시나리오 자체가 성립하지 않는다)

### 4.2 코호트 저장·전달 형식

생성기가 seed 종료 시 `k6/data/cohorts.json`을 export한다.

```json
{
  "heavy":  [ { "id": 101, "username": "user_000101" }, ... ],
  "normal": [ ... ],
  "light":  [ ... ]
}
```

- k6 `setup()`이 이 파일을 읽어 **시나리오 코호트 분량만** `POST /api/v1/auth/login`으로 JWT를 발급하고
  토큰 배열을 반환, VU가 재사용한다(§9.3 — 매 요청 로그인이면 BCrypt가 병목이 되어 인증 비용을 재게 된다)
- 더미 사용자 비밀번호는 전원 동일한 고정값(BCrypt 해시 1개 재사용)이라 k6가 로그인할 수 있다
- 측정에 사용한 cohorts.json은 raw 결과와 함께 커밋한다(§9.6 재현성)

### 4.3 Pull 타임라인 — 마스터 §8의 SQL 그대로

```sql
SELECT p.* FROM posts p
JOIN follows f ON p.author_id = f.followee_id
WHERE f.follower_id = :userId
  AND p.id < :cursor
  AND p.is_deleted = false
ORDER BY p.id DESC LIMIT 25
```

- self-follow 행 덕분에 이 쿼리로 **내 글도 함께 조회된다**(§4.3) — UNION 불필요, 단일 JOIN 유지가
  실행 계획 분석(이 Phase의 핵심 산출물)을 깨끗하게 만든다
- `is_deleted` 조건을 처음부터 포함해 Phase 2와 결과 집합을 맞춘다
- **LIMIT 25로 조회한 뒤 앞 20건을 반환**한다 — Phase 2의 "25개 조회 → 20개 반환" 페이지 규격(§5-2)과
  동일하게 맞춰 두 경로의 응답 계약을 일치시킨다. 21번째 행 존재가 `hasNext`, 20번째 postId가 `nextCursor`
- 응답 형식은 §6의 커서 페이지네이션 규격(`data`/`nextCursor`/`hasNext`)

### 4.4 측정 지점과 k6 시나리오

| 지점 | 조건 | 의미 (§9.2) |
|---|---|---|
| **M0** | Pull, `posts` 조회용 보조 인덱스 없음 | 최악 baseline. PK/FK/`UNIQUE(follower_id, followee_id)` 등 스키마 제약은 유지 — "인덱스 없음"은 조회 최적화용 보조 인덱스의 부재를 뜻한다 |
| **M1** | Pull + 복합 인덱스 (후보 A/B 실측 비교 후 채택) | **"인덱스만으로 얼마나 개선되는가"** — Push 전환 주장의 대조군 |

k6는 §9.1의 open model 시나리오 2종을 그대로 쓴다. `ramping-vus`(closed model)는 서버가 느려지면
부하가 줄어 지연을 과소평가하므로(coordinated omission) 쓰지 않는다.

| 시나리오 | executor | 주입 | 산출 |
|---|---|---|---|
| `saturation` | `ramping-arrival-rate` | 단계 상승. **M0/M1은 예상 처리량에 맞춰 낮은 사다리에서 시작**(예: 10→25→50→100→250 RPS) — 마스터 §9.1의 "측정 지점별 사다리 하향 조정(구조 동일)" 규정에 따름. 100~3,000 사다리를 그대로 주입하면 전 구간 `dropped_iterations>0`으로 측정이 무효가 되기 때문 | p99가 꺾이는 포화점 → 템플릿의 "최대 RPS(p99<200ms)" |
| `slo` | `constant-arrival-rate` | 1,000 RPS 고정 · 5분 · `startTime: '2m'`(JVM 워밍업 이후 계측) | 목표 RPS 성립 여부. M0/M1에서 미성립이 예상되며, **그 사실 자체("1,000 RPS 주입 불가")가 baseline의 결과**다 |

게이트와 기록:

- `dropped_iterations == 0`이 아니면 **그 런은 폐기하고 재실행**한다(§9.1 — 주입량을 못 채우면 측정 무효)
- `p(99)<200` threshold는 M0/M1에서 실패가 예상된다. 이는 기록 대상이지 측정 무효 사유가 아니다
- 코호트 3종(`light`/`normal`/`heavy`) 각각 측정해 "팔로잉 수에 따른 응답 시간 변화"를 기록한다(§8 Phase 1 기록 항목).
  정량 목표의 p99 조건(§2)이 `heavy` 코호트 기준이므로 대표 수치는 `heavy`로 보고한다

### 4.5 측정 프로토콜 (§9.3 준수 사항)

| 항목 | 실행 방법 |
|---|---|
| 데이터 동일성 | 매 반복 전 MySQL Docker 볼륨 스냅샷 복원. 스냅샷은 지점별 이원 관리 — `snap-m0`(인덱스 없음) / `snap-m1`(채택 인덱스 적용). 복원 후 인덱스 재생성 시간을 없애기 위함 |
| cold/warm | Phase 1에는 Redis 타임라인 경로가 없으므로 buffer pool 기준으로 기록 — 복원 직후 1회(cold 참고치) + 워밍업 후 본 측정(warm). 두 조건 모두 리포트에 기록 |
| JVM 워밍업 | 계측은 워밍업 2분 이후 시작 (`startTime: '2m'`) |
| 반복 | 3회 반복, **중앙값 보고 + 최대-최소 편차 병기** |
| 사용자 추출 | 5,000명 코호트에서만, 시나리오별 코호트 고정 |
| JWT | `setup()` 사전 발급, VU 재사용 |
| 부하 생성기 격리 한계 | k6는 호스트에서 8코어를 앱과 공유 — **측정 중 k6 프로세스 CPU 사용률을 주기 샘플링해 raw와 함께 기록**하고 리포트에 한계를 명시한다 |
| 서버 사이드 계측 | Micrometer `@Timed`로 DB/직렬화 구간 분해. `클라이언트 지연 − 서버 내부 지연 = 큐잉 시간`이 포화의 증거 |
| 병목 판정 도구 | slow log + `EXPLAIN ANALYZE`(실측) + JFR. `EXPLAIN`(추정)과의 차이도 분석거리로 기록(§3) |

### 4.6 buffer pool과 하향 트리거

- my.cnf에 `innodb_buffer_pool_size = 1G`(MySQL 컨테이너 2G 한도 내, §3)
- 히트율 = `1 − (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)` — 측정 중 상태 변수로 산출
- **측정 중 히트율 95% 미만이면 게시글을 200만으로 재조정하고 그 사실을 리포트에 기록한다**(§8 하향 트리거).
  발동 시: posts만 재생성 → 분포 검증 → snap 재생성 → 이후 모든 측정은 200만 기준.
  W4 금요일(P1-10)에 조기 판정해 W5 본 측정이 번복되지 않게 한다
- 300만으로 잡은 이유(§8): 총 데이터가 buffer pool을 넘으면 측정치가 디스크 I/O 변동성에 지배당한다.
  Phase 1의 병목은 데이터 크기가 아니라 동시성(정렬/CPU 큐잉)에서 나오므로 300만으로도 재현된다

### 4.7 배치 적재 실행 상세

- **JDBC Batch Insert** — JPA로 넣으면 며칠 걸린다(§8). 생성기는 JDBC만 사용
- JDBC URL에 `rewriteBatchedStatements=true`(미적용 시 multi-row로 재작성되지 않아 자릿수 단위로 느리다),
  autocommit off, `addBatch` 1,000행 단위 실행, 10,000행 단위 커밋
- posts는 16스레드 병렬 — 스레드당 노드ID 0~15를 고정 소유해 ID 충돌이 원천 차단되고,
  스레드(=노드) 하나가 같은 ms에 4,096개(12bit 시퀀스)를 넘지 않게 시퀀스를 관리한다
- 타임스탬프 주입형 팩토리 `of(epochMilli, nodeId, sequence)`는 **더미 소스셋에만 존재**한다(§4.2 —
  프로덕션에 백데이팅 API를 노출하면 그것이 곧 ID 위조 경로다). `created_at`은 주입한 epochMilli와
  동일하게 기록해 "ID 순서 = 시간 순서" 전제를 더미에서도 유지한다
- 생성기는 고정 RNG 시드를 사용해 동일 입력에서 동일 분포를 재현한다
- users/follows의 `created_at`은 측정에 영향이 없으므로 게시글 분포보다 이전의 과거 임의값으로 채운다

---

## 5. 산출물

| 분류 | 경로 (제안) | 내용 |
|---|---|---|
| 코드 | `src/datagen/...` (더미 소스셋) | JDBC 생성기(users/follows/posts), 분포 배정 로직, 코호트 export, 타임스탬프 주입 Snowflake 팩토리 |
| 코드 | `src/main/...` | Pull 타임라인 API(`GET /timeline`), Micrometer `@Timed` 구간 계측 |
| 마이그레이션 | `src/main/resources/db/migration/` | 인덱스 후보 적용/되돌림 Flyway 마이그레이션 — **이력 자체가 실패한 시도의 기록**(§3) |
| 설정 | `docker/mysql/my.cnf` | `innodb_buffer_pool_size=1G`, slow log 설정 |
| 부하 스크립트 | `k6/timeline-read.js` | saturation/slo 시나리오, `setup()` JWT 발급 |
| 데이터 | `k6/data/cohorts.json` | 코호트 5,000명 id/username (측정에 쓴 버전 커밋) |
| 자동화 | `Makefile` | `make seed` / `make db-snapshot` / `make db-restore` / `make bench-m0` / `make bench-m1` |
| 측정 문서 | `docs/perf/m0.md`, `docs/perf/m1.md` | §9.7 템플릿 준수. 환경·결과·원인 분석·"M1이 충분하지 않았던 이유"·남은 문제 |
| 원시 결과 | `docs/perf/raw/m0/run{n}.json`, `docs/perf/raw/m1/run{n}.json` | k6 JSON summary(측정별 디렉토리 + `run{n}.json` 파일명 규칙) + k6 CPU 샘플링 로그 |

---

## 6. 리스크와 대응

이 Phase 고유의 리스크만 다룬다. (전체 리스크는 마스터 §12)

| 리스크 | 징후 | 대응 |
|---|---|---|
| 적재 시간 폭주 (600만+ 행) | 진행률 로그 기준 완료 예상 시각 초과 | `rewriteBatchedStatements` 적용 확인(누락 시 자릿수 단위 저하), 배치/커밋 단위 튜닝, 야간·주말 실행. P1-05를 완충일로 사용 |
| 분포 생성 실패 (중복·self 재추출 미수렴) | 검증 쿼리 불일치 | 슬롯 스왑 보정 로직. 게이트: 코호트 팔로잉 수는 정확 일치 강제, 계층 팔로워 합계는 ±0.1% 허용 후 리포트에 실측 분포 기재 |
| buffer pool 히트율 < 95% | 측정 중 상태 변수 | 마스터 규정대로 게시글 200만 하향 + 리포트 기록. P1-10에서 조기 판정해 본 측정 번복 방지. 재생성·재스냅샷 비용(반나절)은 W5 앞 주말로 흡수 |
| M0에서 측정 자체가 불성립 (최저 rate에서도 drop) | `dropped_iterations > 0` 연속 | rate 사다리 추가 하향, `maxVUs` 상향(호스트 메모리 한도 내). 그래도 불성립이면 "주입 가능 최대치"를 M0의 결과로 기록 — baseline은 낮을수록 서사에 유리하며 조작이 아니다 |
| k6-앱 CPU 경합으로 수치 오염 | k6 CPU 샘플링 고점 | §9.3 규정대로 CPU 기록·리포트 명시. 경합 구간의 런은 재실행 |
| 디스크 소진 (스냅샷 아카이브 누적) | 여유 55GB 감소 | 지점별 최신 스냅샷 1개만 유지, tar 압축 |
| `setup()` JWT 발급 지연 | k6 setup 타임아웃 | 시나리오 코호트 분량만 발급, `http.batch` 병렬화, `setupTimeout` 상향. JWT TTL이 런 길이를 넘는지 사전 확인 |

---

## 7. 마스터 체크박스 매핑

마스터 §8 Phase 1의 체크박스 ↔ 이 문서의 작업 번호. 누락 없음을 보인다.

| 마스터 §8 Phase 1 체크박스 | 이 문서 작업 |
|---|---|
| 더미 데이터 생성 (JDBC Batch Insert) — 사용자 10만 | P1-01 |
| 〃 — 팔로우 관계 300만 (분포표) | P1-02, P1-03 |
| 〃 — 게시글 300만, 본문 평균 80B | P1-04 |
| 〃 — 시간 분포 7일 25% / 8~30일 35% / 31~180일 40% | P1-04 |
| 〃 — 노드ID 0~15로 16스레드 병렬 생성 (ms당 4,096 시퀀스 한계 회피) | P1-04 |
| `innodb_buffer_pool_size = 1G` 설정 | P1-05 (판정: P1-10) |
| Pull 방식으로 타임라인 조회 구현 (SQL 그대로, `is_deleted` 포함) | P1-06 |
| k6 시나리오 작성 (→ §9, open model) | P1-08 (리허설: P1-09) |
| **M0 측정** (인덱스 없음) | P1-11, P1-12 |
| `EXPLAIN ANALYZE`로 실행 계획 분석, 인덱스 설계 | P1-12, P1-13 |
| **M1 측정** (복합 인덱스 적용) | P1-14, P1-15 |

§8 Phase 1의 "기록할 것" 3항목도 산출물에 귀속된다:

| 기록 항목 | 귀속 |
|---|---|
| 팔로잉 수에 따른 응답 시간 변화 (`light`/`normal`/`heavy`) | m0.md·m1.md 코호트별 결과 표 (P1-11, P1-14) |
| M0 대비 M1의 개선 폭 | m1.md (P1-15) — "인덱스만으로는 한계가 있었다"의 근거 |
| 병목 위치 (slow log + `EXPLAIN ANALYZE` + JFR) | m0.md·m1.md 원인 분석 절 (P1-12, P1-15) |
