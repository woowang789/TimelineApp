# Phase 2a — Push 전환 (W6~W7 · 2026-09-28 ~ 2026-10-11 · 선행: [Phase 1](./20-phase-1-baseline.md))

> 마스터: [../../timeline-project.md](../../timeline-project.md) §8 Phase 2a · 이전: [Phase 1 — 더미 데이터 & 첫 측정](./20-phase-1-baseline.md) · 다음: [Phase 2b — 필수 쟁점 & 정합성](./40-phase-2b-consistency.md)

타임라인 조회를 Pull(DB JOIN)에서 Push(fan-out on write + Redis Sorted Set)로 전환하고,
§7.13의 캐시 스탬피드 대응까지 얹은 뒤 **M2(cold) / M3(warm)를 측정**한다.

이 Phase가 끝나면 **"Pull → Push로 p99를 개선했다"는 완결된 서사 하나가 성립한다.
여기서 중단해도 포트폴리오가 된다** (마스터 §8 Phase 2a 완료 조건).
따라서 W7 후반의 리포트 작성은 부속 작업이 아니라 이 Phase의 본질이다.

---

## 1. 목표와 완료 조건

### 목표

1. 글 작성 시 팔로워 타임라인으로의 **동기 fan-out** 구현 (§5-6, §7.3)
2. `timeline:{userId}` Sorted Set 기반 **2단계 조회** 구현 (§5-1, §5-2)
3. **§7.13 전체 구현** — 세마포어 / 락 / negative cache(tombstone) / Circuit Breaker
4. `post:{postId}` JSON 캐시 + 좋아요 갱신 규칙 (§4.4)
5. §9.3 프로토콜에 따른 **M2 / M3 측정과 리포트**

### 완료 조건 (검증 가능한 형태)

- [ ] 통합 테스트 전부 green: fan-out(본인 포함), 타임라인 상한 500, 2단계 조회, soft delete 필터, 폴백 100개 채움, lock / semaphore / tombstone / Circuit Breaker 각각의 동작 테스트
- [ ] 동일 사용자에 대해 **Pull 쿼리 결과와 Push 조회 결과의 postId 시퀀스가 일치**하는 통합 테스트 통과 (§4.3의 self-follow 전제 확인. §9.4 동등성 검증의 CI 상시 편입은 [Phase 2b](./40-phase-2b-consistency.md))
- [ ] `docs/perf/m2.md`, `docs/perf/m3.md` 존재 — §9.7 템플릿 준수, 3회 반복 중앙값 + 최대-최소 편차 병기, `dropped_iterations = 0`, k6 원시 결과 커밋 (§9.6)
- [ ] M3 리포트에 **M1 대비 p99 개선 폭이 수치로 기록**되어 있고, "M1이 충분하지 않았던 이유" 절이 채워져 있다 (§9.2, §10 서술 원칙)
- [ ] M3(warm · `heavy` 코호트 · 목표 RPS 주입)에서 **p99 200ms 이하** 도달 여부가 판정되어 있다 — 미달이면 원인 분석이 기록되어 있다 (§2: 절대값보다 개선 폭과 원인 분석)
- [ ] 엔트리 500개 `timeline:{userId}` 키의 **`MEMORY USAGE` 실측값**이 리포트에 기록되어 있다 (§5-4)

---

## 2. 선행 조건

| 선행 산출물 | 출처 | 이 Phase에서의 용도 |
|---|---|---|
| 더미 데이터: 사용자 10만 / 팔로우 300만(S·A·B·C 계층) / 게시글 300만(본문 평균 80B) | Phase 1 | fan-out 대상·측정 데이터. 없으면 이 Phase의 모든 수치가 무의미 |
| MySQL Docker 볼륨 스냅샷 + 복원 절차 | Phase 1 | §9.3 고정 조건 — 매 측정 전 데이터 동일성 보장 |
| Pull 타임라인 쿼리 (self-follow JOIN, `is_deleted` 포함, 커서 방식) | Phase 1 | **폴백 경로로 재사용** — LIMIT만 100으로 축소 (§7.13) |
| M1 시점의 복합 인덱스 구성 (Flyway 반영 상태) | Phase 1 | M2/M3 비교의 **고정 변수**. 이 Phase에서 인덱스를 건드리면 개선을 Push에 귀속할 수 없다 (§9.2) |
| `docs/perf/m0.md`, `docs/perf/m1.md` | Phase 1 | 리포트의 before 수치. "인덱스만으로는 한계"의 증거 |
| k6 open model 스크립트 + `setup()` JWT 사전 발급 + 5,000명 코호트 사용자 목록 | Phase 1 | 시나리오 확장의 기반 (§9.1, §9.3) |
| `bench` compose 프로파일 (Prometheus 0.4G + Grafana 0.3G) | Phase 0 | 측정 시 서버 사이드 계측 (§3) |
| Redis 7 컨테이너 — `maxmemory 1gb` + `allkeys-lru` | Phase 0 | §5-5. 워밍 중 LRU 축출 관찰의 전제 |
| 가입 시 self-follow 행 삽입 | Phase 0 | fan-out 대상에 본인이 포함되는 근거 (§4.3) — Pull/Push 결과 집합 일치의 전제 |
| `DELETE /posts/{postId}` (soft delete) · likes API | Phase 0 | 읽기 시점 필터링과 캐시 갱신(§4.4)의 대상 |
| Testcontainers 통합 테스트 환경 | Phase 0 | 실제 MySQL/Redis로 이 Phase의 모든 검증 수행 |

---

## 3. 작업 분해

작업 단위는 반나절~1일. 개발은 `dev` 프로파일, 측정은 `bench` 프로파일에서 수행한다 (§3).

### W6 (2026-09-28 ~ 2026-10-04) — Push 경로 구현 + 측정 준비

| 일자 | ID | 작업 | 상세 | 검증 |
|---|---|---|---|---|
| 09-28 (월) | T1 | Sorted Set 타임라인 저장소 | `timeline:{userId}` 연산 래퍼: pipeline으로 `ZADD` + `ZREMRANGEBYRANK 0 -501`(상한 500) + `EXPIRE` 7일(§5 키 표). Testcontainers Redis 통합 테스트 기반 마련 | 검증: 501개 ZADD 후 `ZCARD` = 500, 가장 오래된(score 최소) postId가 제거되는 테스트 green. `TTL` 값이 7일로 설정됨 확인 |
| 09-29 (화) | T2 | 동기 fan-out | `POST /posts` — INSERT 트랜잭션 **커밋 후** fan-out 실행(롤백 시 캐시 유령 글 방지). 팔로워를 커버링 인덱스 `(followee_id, follower_id)`로 **500명 청크 커서 조회**(§5-6, followers 캐시 없음), 청크당 T1 pipeline 실행. self-follow 행 덕에 본인 타임라인 자동 포함 | 검증: 통합 테스트 — 팔로워 3명 + 본인 전원의 timeline 키에 postId 존재. 팔로워 1,001명 케이스에서 청크 조회가 3회 실행됨(쿼리 로그). 같은 fan-out 2회 실행 시 결과 동일(ZADD 멱등, §7.3) |
| 09-30 (수) | T3 | post 캐시 + tombstone | `post:{postId}` JSON 캐시: 미스 시 DB 조회 후 `SET ... EX 3600`(TTL 1시간). 부재·삭제 글은 tombstone `SET ... EX 60`(§7.13 negative cache). 좋아요 시 **본인은 `DEL post:{postId}` 후 갱신값 반환**, 타인은 최대 1시간 stale 허용(§4.4) | 검증: 통합 테스트 — TTL 실측(3600/60). 삭제된 글 60초 내 반복 조회 시 DB 쿼리 1회만 실행(tombstone 동작). 좋아요 직후 본인 조회에 `like_count` 즉시 반영 |
| 10-01 (목) | T4 | 2단계 조회 전환 | `GET /timeline` — ① `ZREVRANGEBYSCORE timeline:{userId} (cursor -inf LIMIT 0 25`(첫 페이지는 `+inf`) ② `MGET post:{id} ×25` ③ `is_deleted`·tombstone 필터 후 **최대 20개** + `nextCursor`/`hasNext` 반환. 부족해도 재조회 없이 있는 만큼 반환 — 재조회 상한 3회는 Phase 2b(§7.5) | 검증: 통합 테스트 — 동일 사용자의 Pull 쿼리 결과와 postId 시퀀스 일치. soft delete 글 미노출. 25개 미만 조회 시 `hasNext=false` 판정 |
| 10-02 (금) | T5 | 캐시 미스 폴백 + 워밍 | `EXISTS timeline:{userId} = 0`이면 Pull 쿼리로 **최근 100개만** 조회(§7.13 폴백 축소) → ZADD 일괄 채움 → 채워진 캐시에서 2단계 조회로 응답. §7.6 비활성 복귀가 이 경로에 흡수됨 — 별도 기능 없음 | 검증: 통합 테스트 — `FLUSHALL` 후 첫 조회가 정상 20개 반환 + 해당 키 `ZCARD` = 100(팔로잉 글 100개 이상 사용자 기준). 직후 두 번째 조회는 Pull 쿼리 미실행(쿼리 카운트) |
| 10-03~04 (주말) | T8 | 측정 준비 | k6 시나리오 확장(§9.5): 캐시 전체 삭제 후 조회(M2), `normal`/`heavy` 코호트 조회, 혼합 98/2, 인플루언서(S 계층) 글 작성 **단독** 시나리오(§7.1 — 혼합에 섞지 않는다). `make bench-m2` / `make bench-m3` 타겟(§9.6 한 명령). MySQL 스냅샷 복원 절차 리허설 | 검증: 드라이런에서 `dropped_iterations = 0`. `setup()` JWT 사전 발급·재사용 동작. 스냅샷 복원 후 건수 일치(10만 / 300만 / 300만) |

### W7 (2026-10-05 ~ 2026-10-11) — §7.13 보호 장치 + M2/M3 측정

| 일자 | ID | 작업 | 상세 | 검증 |
|---|---|---|---|---|
| 10-05 (월) | T6 | 스탬피드 방어 ① 락·세마포어 | 폴백 진입 시 `SET timeline:lock:{userId} 1 NX EX 10` — 실패하면 짧게 대기 후 캐시 재조회(다른 요청이 채우는 중). 폴백 동시 실행은 **세마포어 20개**, `tryAcquire(500ms)` 실패 시 `503 + Retry-After: 1`(§6, §7.13). HikariCP 풀 크기를 확인해 폴백이 커넥션을 전부 먹지 않는 구성인지 점검 | 검증: 동일 사용자 동시 요청 100건 테스트에서 Pull 쿼리 실행 1회(락 동작 — §7.13 검증 항목의 선행 구현). 세마포어 포화 테스트에서 503 응답과 `Retry-After` 헤더 확인 |
| 10-06 (화) | T7 | 스탬피드 방어 ② CB + 계측 | Resilience4j CircuitBreaker를 폴백 경로에 **DB 보호 방향**으로 적용 — 폴백 실패율 초과 시 open, open 중에는 DB를 치지 않고 즉시 503(§7.13). Micrometer `@Timed`로 DB / Redis / 직렬화 구간 분해(§9.3 서버 사이드 계측) | 검증: 폴백에 예외 주입 시 CB가 open으로 전이하고 이후 요청이 DB 쿼리 없이 503 반환되는 테스트. `/actuator/prometheus`에 구간별 타이머 노출 확인 |
| 10-07 (수) | T9 | **M2 측정 (cold)** | 스냅샷 복원 → JVM 워밍업 → **`FLUSHALL` 직후 계측 시작** → 3회 반복(§9.3). 기록: p50/p99, **503 비율**, 폴백 구간 소요(서버 계측), 캐시가 채워지며 회복되는 곡선, k6 프로세스 CPU 사용률 | 검증: `docs/perf/m2.md` 작성(§9.7 템플릿) + 원시 k6 결과 커밋. 3회 중앙값·편차 병기, `dropped_iterations = 0` |
| 10-08 (목) | T10 | **M3 측정 (warm)** | 스냅샷 복원 → `FLUSHALL` → **동일 코호트로 5분 워밍** → saturation(포화점) + slo(1,000 RPS 고정, `heavy` 코호트) + 혼합 98/2 + fan-out 지연 단독 시나리오, 각 3회 반복. **`ZCARD` = 500인 `timeline:{userId}` 키의 `MEMORY USAGE` 실측**(§5-4 — listpack→skiplist 전환 때문에 추산 금지) | 검증: `docs/perf/m3.md` + 원시 결과 커밋. `p(99)<200` threshold 판정 기록. 워밍 종료 시 코호트 키 존재율 샘플 확인(`EXISTS`). MEMORY USAGE 실측값 기입 |
| 10-09 (금) | — | 측정 재실행 슬롯 | T9/T10에서 `dropped_iterations ≠ 0` 등으로 폐기된 측정이 있으면 여기서 재실행한다. 문제가 없으면 T11(리포트)을 앞당겨 시작 | 검증: 재실행이 발생했으면 해당 리포트에 사유 기록 |
| 10-10~11 (주말) | T11 | 리포트 완성 + 태그 후보 커밋 기록 | m2/m3.md에 §9.7 템플릿의 "M1이 충분하지 않았던 이유"와 "남은 문제"(동기 fan-out의 쓰기 지연 → Phase 2b·3의 동기) 절 완성. M0/M1/M2/M3 비교 표 작성. **태그 후보 커밋 기록** — M3 완료 커밋 해시를 `docs/perf/m3.md`에 남긴다 (태그 자체는 [Phase 4′](./50-phase-4-stabilize-document.md) 4-13에서 Phase 2b 재측정 완료 커밋에 부여) | 검증: §9.7 템플릿 전 항목이 채워짐. M1 대비 p99 개선 폭이 수치로 명시됨. M3 완료 커밋 해시가 `m3.md`에 기록됨 |

---

## 4. 기술 상세

마스터의 결정을 실행 관점으로 재서술한다. 결정의 근거는 각 절 참조가 원본이다.

### 4.1 쓰기 경로 — 동기 fan-out (§5-6 · §4.3 · §7.3)

```
POST /posts
  1) INSERT posts (Snowflake id)                 -- 트랜잭션
  2) 커밋 후 동기 fan-out:
     loop:
       SELECT follower_id FROM follows
       WHERE followee_id = :authorId AND follower_id > :lastId
       ORDER BY follower_id LIMIT 500            -- 커버링 인덱스 (followee_id, follower_id)
       청크마다 pipeline:
         ZADD timeline:{followerId} {postId} {postId}
         ZREMRANGEBYRANK timeline:{followerId} 0 -501   -- 상한 500
         EXPIRE timeline:{followerId} 7d
  3) 응답
```

- **followers 캐시는 만들지 않는다** (§5-6). fan-out은 읽기 경로가 아니므로 수십 ms의 DB 조회는 문제가 아니고, TTL 캐시는 신규 팔로워의 글 유실을 만든다.
- **self-follow 행 덕에 본인이 fan-out 대상에 포함**된다 (§4.3). fan-out이 동기이므로 이 Phase에서는 read-your-own-write(§7.7)가 자동 충족된다. 본인만 동기 ZADD로 분리하는 처리는 비동기화와 함께 [Phase 2b](./40-phase-2b-consistency.md)의 몫이다.
- `active:users` 필터링과 Kafka 발행은 [Phase 3](./60-phase-3-async-hybrid.md)이다 (§8). **갱신(생산자 ZADD) 로직도 Phase 2에는 없다 — Phase 3에서 신규 구현한다.** 이 Phase는 **전체 팔로워**에게 동기 fan-out한다.
- ZADD는 멱등이라 재시도해도 중복이 없다 (§5-3, §7.3). 중단·재처리의 정식 실증은 Phase 2b(§9.4)에서 한다.
- Hybrid가 없으므로 최대 fan-out은 S 계층 팔로워 20,000명 = **청크 40개를 동기로 처리**한다. 이때의 쓰기 응답 지연을 단독 시나리오로 수치화하는 것이 Phase 2b·3으로 가는 서사의 근거다 (§9.5).

### 4.2 읽기 경로 — 2단계 조회 (§5-1 · §5-2)

```
GET /timeline?cursor={postId}&size=20
  1) ZREVRANGEBYSCORE timeline:{userId} (cursor -inf LIMIT 0 25   -- 첫 페이지는 +inf, 커서는 exclusive
  2) MGET post:{id} × 25
     캐시 미스 → DB 조회 → 존재: SET EX 3600 / 부재·삭제: tombstone SET EX 60
  3) is_deleted·tombstone 필터 → 최대 20개 반환, nextCursor = 마지막 postId
```

- 타임라인에는 **post_id만 저장**한다 (§5-1). soft delete 플래그 하나로 흩어진 모든 타임라인에 삭제가 반영된다.
- Snowflake ID가 score이므로 정렬 = 시간순이고, 커서는 ID 하나로 충분하다 (§4.2).
- 필터 후 20개가 안 되어도 이 Phase에서는 재조회하지 않는다. **재조회 상한 3회와 삭제율 구간 측정은 Phase 2b** (§7.5).

### 4.3 폴백과 §7.13 보호 장치

"키 없음 → Pull 폴백"은 개별 캐시 미스의 답이지 전면 장애의 답이 아니다 (§5-7). 폴백 경로 자체에 보호 장치를 내장한다.

```
EXISTS timeline:{userId} = 0 → 폴백 진입
  a) SET timeline:lock:{userId} 1 NX EX 10
     실패 → 다른 요청이 채우는 중. 짧게 대기 후 캐시 재조회
  b) semaphore(20).tryAcquire(500ms)
     실패 → 503 + Retry-After: 1  (부분 결과를 지어내지 않고 명시적으로 거절, §6)
  c) CircuitBreaker(DB 보호 방향) closed:
     Pull 쿼리 LIMIT 100 (§7.13 폴백 축소 — 500개 전체 재구축은 무겁고 사용자가 보는 건 첫 페이지)
     → ZADD 일괄 + EXPIRE 7d → 채워진 캐시에서 2단계 조회로 응답
     CB open → DB를 치지 않고 즉시 503
```

- **세마포어 20은 DB 커넥션 풀 크기와 연동**해 확정한다 — 폴백이 커넥션을 전부 먹으면 정상 경로까지 죽는다 (§7.13).
- **tombstone(TTL 60초)** 이 없으면 §7.5의 대량 삭제 시나리오가 DB 부하로 증폭된다 (§7.13).
- §7.6(비활성 복귀)은 이 경로에 흡수된다 — 로그인 시 사전 워밍이나 비동기 재구축을 만들지 않는다.
- Redis 전면 장애(부하 중 `SHUTDOWN`) 하에서의 검증은 [Phase 4′](./50-phase-4-stabilize-document.md)의 장애 시나리오다. 이 Phase는 구현과 단위·통합 수준 검증까지다.

### 4.4 post 캐시와 좋아요 (§4.4 · §7.4)

- 카운터의 진실은 DB다. `likes` 행 삽입 + `UPDATE posts SET like_count = like_count + 1`을 한 트랜잭션으로.
- 읽기는 `post:{postId}` 캐시(TTL 1시간) 경유 — **타인에게 최대 1시간 stale을 명시적으로 허용**한다.
- 좋아요를 누른 **본인에게는 `DEL post:{postId}` 후 갱신값을 직접 반환** — 즉시 반영.
- `like:count:{postId}` 같은 Redis 카운터는 만들지 않는다 (§5 — 설계에서 뺀 키).

### 4.5 M2 / M3 측정 실행 (§9.1~9.3 · §9.5 · §9.7)

**공통 프로토콜** (§9.3): 매 측정 전 MySQL 볼륨 스냅샷 복원 → 캐시 상태 조성 → open model 주입(§9.1의 saturation / slo 시나리오) → **3회 반복, 중앙값 보고, 편차 병기**. JWT는 `setup()` 사전 발급. 사용자는 5,000명 코호트에서만 추출하고 시나리오별로 고정. k6 CPU 사용률을 함께 기록. `dropped_iterations ≠ 0`이면 그 측정은 폐기.

| 구분 | M2 (cold) | M3 (warm) |
|---|---|---|
| 목적 | **폴백 경로 비용** (§9.2) | **본 성능** — p99 200ms · RPS 1,000 판정 (§2) |
| 캐시 조성 | JVM 워밍업 후 `FLUSHALL`을 다시 실행하고 곧바로 계측 — "FLUSHALL 직후"와 "워밍업 2분 후 계측"을 모두 만족시키는 실행 순서 | `FLUSHALL` → **동일 코호트로 5분 워밍** → 계측. 워밍 종료 시 키 존재율 샘플 확인 |
| 시나리오 | 캐시 전체 삭제 후 조회 (§9.5) | saturation + slo(`heavy`) + 혼합 98/2 + **인플루언서 작성 단독**(fan-out 지연, §7.1) |
| 핵심 기록 | p99 · **503 비율** · 폴백 구간 소요 · 회복 곡선 | p99 · 최대 RPS · M1 대비 개선 폭 · fan-out 지연 · `MEMORY USAGE` 실측 |

- M2에서 503 다수 발생은 측정 실패가 아니라 §7.13이 설계한 "명시적 거절"의 관측이다. §9.1의 `http_req_failed` threshold는 SLO 검증(warm)용이므로 M2에서는 합격 기준이 아니라 기록 지표로 다룬다.
- `MEMORY USAGE`는 `ZCARD` = 500인 키를 대상으로 실측한다. 500개는 `zset-max-listpack-entries`(128)를 넘어 skiplist로 전환되므로 80B 추산은 낙관적이다 (§5-4). 실측값은 `docs/perf/m3.md`에 기록하고, 실서비스 스케일 계산으로의 확장은 Phase 4′ README에서 한다.
- 워밍·측정 중 LRU 축출(`evicted_keys`)이 관측되면 그 자체를 기록한다 — §5-5가 말하는 "그 자체가 실험"이다.

### 4.6 이 Phase에서 하지 않는 것 (경계)

| 항목 | 어디로 | 근거 |
|---|---|---|
| 재조회 상한 3회 + 삭제율 0~90% 측정 | [Phase 2b](./40-phase-2b-consistency.md) | §7.5, §8 |
| read-your-own-write의 비동기 분리 (본인만 동기 ZADD + 지연 5초 주입 검증) | Phase 2b | §7.7 — 이 Phase는 전체가 동기라 자동 충족 |
| 언팔로우 A/B/C 실측, 팔로우 백필 N=20 | Phase 2b | §7.8, §7.9 |
| Pull==Push 동등성 검증의 CI 상시 편입 | Phase 2b | §9.4 |
| Kafka fan-out · `active:users` 필터 — 갱신(생산자 ZADD) 로직도 Phase 2에는 없으며 Phase 3에서 신규 구현 · Hybrid(임계치 5,000) | [Phase 3](./60-phase-3-async-hybrid.md) | §7.11, §7.2, §8 |
| Redis `SHUTDOWN` 장애 시나리오, Grafana 대시보드, README 완성 | [Phase 4′](./50-phase-4-stabilize-document.md) | §7.13 검증, §8 |
| 2계층 캐시(Caffeine), Redis 클러스터 | 하지 않음 / 언급만 | §7.10, §7.12 |

---

## 5. 산출물

| 분류 | 경로 | 내용 |
|---|---|---|
| 코드 | `src/main/java/<base>/timeline/` | `FanoutService`(동기 fan-out, 청크 조회), `TimelineRedisRepository`(ZADD·trim·EXPIRE pipeline), `TimelineQueryService`(2단계 조회), `TimelineFallbackService`(폴백 + lock/semaphore/CB), `PostCacheService`(post 캐시 + tombstone + 좋아요 DEL). 클래스명은 가칭 — 패키지 구조는 [Phase 0](./10-phase-0-foundation.md) 산출물을 따른다 |
| 설정 | `src/main/resources/application*.yml` | Resilience4j CircuitBreaker 설정(폴백 경로), 세마포어·풀 크기 관련 상수 |
| 테스트 | `src/test/java/` 하위 | T1~T7의 Testcontainers 통합 테스트 (fan-out 본인 포함 / 상한 500 / Pull==Push 시퀀스 일치 / 폴백 100 / 락 중복 방지 / 세마포어 503 / tombstone / CB 전이) |
| 부하 스크립트 | `k6/` | cold 조회(M2), warm 조회(saturation·slo), 혼합 98/2, 인플루언서 작성 단독 시나리오 |
| 자동화 | `Makefile` | `bench-m2`, `bench-m3` — 스냅샷 복원부터 리포트 생성까지 한 명령 (§9.6) |
| 리포트 | `docs/perf/m2.md`, `docs/perf/m3.md` | §9.7 템플릿. M0/M1/M2/M3 비교 표, 503 비율, fan-out 지연, `MEMORY USAGE` 실측값(m3.md 내) |
| 원시 결과 | `docs/perf/raw/m2/run{n}.json`, `docs/perf/raw/m3/run{n}.json` | 측정별 하위 디렉토리에 k6 원시 결과 3회분 (§9.6 — 가공된 표만으로는 신뢰가 안 간다) |
| 태그 후보 기록 | `docs/perf/m3.md` 내 M3 완료 커밋 해시 | `v2-push` 태그는 여기서 만들지 않는다 — Phase 4′ 4-13에서 Phase 2b 재측정 완료 커밋에 부여 (§9.6) |

---

## 6. 리스크와 대응

이 Phase 고유의 것만 적는다. (완주·리소스 등 공통 리스크는 마스터 §12)

| 리스크 | 대응 |
|---|---|
| S 계층(팔로워 20,000) 글 작성 시 동기 fan-out으로 응답이 수 초 지연 | **실패가 아니라 의도된 관찰이다.** Hybrid 이전 상태의 한계를 단독 시나리오로 수치화해 Phase 2b·3의 동기로 기록한다 (§9.5). 혼합 시나리오의 작성 2%는 코호트 사용자(대부분 C 계층)가 수행하므로 fan-out 지연이 혼합 측정을 오염시키지 않는다 (§7.1) |
| M2(cold)에서 세마포어 20 제한으로 503 다수 발생 → threshold fail로 오독 | §9.1 thresholds는 SLO 검증용임을 리포트에 명시하고, M2에서는 503 비율을 별도 지표로 기록한다. 측정 유효성 기준은 `dropped_iterations = 0`만 유지한다 |
| 5분 워밍이 5,000명 코호트를 다 덮지 못해 M3에 콜드 미스가 혼입 | 워밍 트래픽을 측정과 **동일 코호트로 고정**(§9.3)하고, 워밍 종료 시 키 존재율을 샘플링(`EXISTS`). 미달이면 워밍을 연장하고 그 사실을 리포트에 기록한다 |
| 코호트 워밍 중 maxmemory 1GB 도달 → LRU 축출로 warm 상태가 흔들림 | 축출은 은폐할 오염이 아니라 관찰 대상이다 (§5-5). `evicted_keys`를 계측에 포함하고 발생 시 리포트에 명시한다 |
| 세마포어(20)와 HikariCP 풀의 불일치 — 폴백이 커넥션을 독점하면 정상 경로까지 사망 | T6에서 풀 크기와의 관계를 확인하고(§7.13), 부하 중 커넥션 대기 지표(Hikari 메트릭)를 함께 기록한다 |
| tombstone과 본문 JSON이 같은 키 공간(`post:{postId}`)을 공유 — 판별 버그 시 삭제 글 노출 또는 정상 글 누락 | tombstone 판별을 단위 테스트로 고정하고, T4의 필터 통합 테스트(삭제 글 미노출)로 회귀를 막는다 |
| fan-out pipeline 도중 프로세스 중단 → 일부 팔로워만 수신 | ZADD 멱등이라 재실행해도 중복은 없다 (§7.3). 이 Phase에서는 요청 실패로 표면화될 뿐 데이터 오염이 아니며, 중단·재처리의 정식 실증은 Phase 2b(§9.4)·Phase 3에서 수행한다 |
| 측정 중 인덱스·스키마를 "개선"하고 싶은 유혹 | 이 Phase에서 DB 스키마는 동결한다. 바꾸는 순간 M3의 개선을 Push에 귀속할 수 없다 (§9.2) |
| W7이 밀림 (구현 지연·측정 재실행 등) | **축소 우선순위**: 혼합/단독 시나리오의 반복 횟수부터 축소한다. `m2.md`/`m3.md` 리포트 작성은 축소 불가 — 이 Phase의 본질이다 |

---

## 7. 마스터 체크박스 매핑

마스터 §8 Phase 2a의 체크박스와 이 문서의 작업 번호 대응. 누락 없음.

| 마스터 §8 Phase 2a 체크박스 | 이 문서의 작업 |
|---|---|
| 게시글 작성 시 팔로워 타임라인에 fan-out (동기) | T2 |
| Redis Sorted Set 타임라인 구현 | T1, T2 |
| 2단계 조회 (ID 목록 → 본문 MGET) | T4 |
| 캐시 미스 시 DB 폴백 + 캐시 워밍 | T5 |
| 타임라인 길이 제한 (500개) | T1 (trim), T2 (fan-out 시 적용) |
| soft delete + 읽기 시점 필터링 | T3 (tombstone), T4 (`is_deleted` 필터) |
| §7.13 캐시 스탬피드 대응 (세마포어 / 락 / negative cache / Circuit Breaker) | T3 (negative cache), T5 (폴백 축소 100), T6 (락·세마포어·503), T7 (Circuit Breaker) |
| M2(cold) / M3(warm) 측정 | T8 (준비), T9 (M2), T10 (M3), T11 (리포트) |

완료 조건 "Pull → Push로 p99를 개선했다는 완결된 서사"는 T9~T11의 산출물(`docs/perf/m2.md`, `m3.md` + 원시 결과)로 성립한다. `v2-push` 태그는 여기서 만들지 않는다 — Phase 4′ 4-13에서 Phase 2b 재측정 완료 커밋에 부여된다.
