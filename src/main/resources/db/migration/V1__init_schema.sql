-- =====================================================================================
-- V1 — 초기 스키마
-- 근거: timeline-project.md §4.1 ERD / docs/roadmap/10-phase-0-foundation.md 4.3절
--
-- 스키마의 유일한 관리자는 Flyway다. Hibernate는 검증만 한다(ddl-auto: validate).
-- 인덱스 추가/되돌림도 전부 마이그레이션으로 남긴다 — 그 이력 자체가 포트폴리오다(마스터 §3).
--
-- 이 마이그레이션의 핵심 규칙
--   posts 에 조회용 보조 인덱스를 넣지 않는다. 아래 posts 블록의 주석이 그 이유다.
--
-- 공통 결정 3가지
--
--  1) 엔진 — InnoDB. FK 제약과 트랜잭션이 필요하다.
--
--  2) 문자셋 — utf8mb4 / utf8mb4_0900_ai_ci 를 테이블마다 명시한다.
--     utf8mb3 는 4바이트 문자(이모지)를 담지 못한다. content 는 사용자 입력 본문이므로 선택지가 없다.
--     콜레이션은 MySQL 8.0 서버 기본값과 같은 값이지만 그래도 적는다 —
--     서버 기본값에 의존하면 컨테이너 이미지 태그가 바뀌는 순간 조용히 달라지고,
--     그 차이는 측정 재현성을 깨뜨린다(마스터 §9.3 "측정 전 스냅샷 복원").
--
--  3) DATETIME 정밀도 — 전 컬럼 DATETIME(6) (마이크로초)
--     · 왜 (6)인가: Hibernate 6의 MySQL 방언은 java.time.LocalDateTime 을 datetime(6) 으로 매핑한다
--       (MySQLDialect 의 기본 timestamp precision = 6). 스키마를 여기에 맞추면
--       "Hibernate가 기대하는 타입"과 "실제 컬럼"이 문자 그대로 같아진다.
--     · ddl-auto: validate 와의 관계: Hibernate 스키마 검증기는 타입명 prefix 비교로 통과 여부를 정하므로
--       datetime(0) 도 사실은 통과한다. 하지만 "통과한다"와 "일치한다"는 다르다.
--       validate 를 통과시키려고 정밀도를 고른 게 아니라, 매핑과 어긋날 여지를 없애려고 골랐다.
--     · 정밀도 0의 실제 손해: MySQL 은 DATETIME 에 소수점 이하를 넣을 때 버리지 않고 반올림한다.
--       created_at 이 최대 +0.5초 미래로 밀린다는 뜻이고, Snowflake id 순서와 created_at 순서를
--       대조하는 검증(§4.2 — 더미 데이터의 ID/시간 정합성)에서 이 오차가 그대로 노이즈가 된다.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- users
-- -------------------------------------------------------------------------------------
CREATE TABLE users
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(50)  NOT NULL,
    password         VARCHAR(255) NOT NULL, -- BCrypt 해시. 60자지만 알고리즘 교체 여지를 두고 255로 잡는다.
    nickname         VARCHAR(50)  NOT NULL,

    -- 비정규화 팔로워 수. 카운터의 SoT 는 DB 이고 원자적 UPDATE 로만 증감한다(부록 B).
    -- self-follow 행(follower_id = followee_id)은 이 값에서 제외한다 —
    -- 가입 시 follows 에 1건이 들어가지만 follower_count 는 0으로 남는다(§4.3의 "대가").
    follower_count   INT          NOT NULL DEFAULT 0,

    -- Hybrid 분기 플래그. 임계치 5,000 · 단방향 승격만 있고 강등은 없다(마스터 §7.2).
    is_influencer    BOOLEAN      NOT NULL DEFAULT FALSE,

    -- 승격 시각. Hybrid 에서 "Push 로 이미 쌓인 구간"과 "Pull 로 머지해야 할 구간"을 가르는 경계다.
    -- 컬럼은 지금 만들고 채우는 로직은 Phase 3 이다.
    -- 나중에 ALTER 로 붙이면 그 마이그레이션이 설계 누락의 기록이 되어 버린다 —
    -- 설계 단계에서 정해진 컬럼은 설계 시점에 넣는다.
    influencer_since DATETIME(6)  NULL,

    created_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- -------------------------------------------------------------------------------------
-- follows
--
-- ※ 가입 시 self-follow 행(follower_id = followee_id)을 1건 삽입한다(§4.3).
--   Pull(JOIN)과 Push(직접 ZADD)의 결과 집합을 같게 만들어 Phase 간 p99 비교를 성립시키는 장치다.
--   DB 제약으로는 막지도 강제하지도 않는다 — 삽입은 가입 트랜잭션이, self 행에 대한
--   follow/unfollow 요청 차단(400)은 API 계층이 책임진다(작업 0.10).
-- -------------------------------------------------------------------------------------
CREATE TABLE follows
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    follower_id BIGINT      NOT NULL, -- 팔로우 하는 쪽
    followee_id BIGINT      NOT NULL, -- 팔로우 당하는 쪽
    created_at  DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- 중복 팔로우 방지 제약이자, Pull 조회의 진입 인덱스를 겸한다(§4.1).
    -- Pull 은 WHERE f.follower_id = ? 로 시작해 followee_id 목록을 얻는데,
    -- 이 UNIQUE 가 (follower_id, followee_id) 순서라 그 조회가 인덱스만으로 끝난다.
    UNIQUE KEY uk_follows_follower_followee (follower_id, followee_id),

    -- fan-out 대상(= 나를 팔로우하는 사람들) 조회용 커버링 인덱스. Phase 2a 에서 쓴다.
    -- 위 UNIQUE 와 컬럼 순서가 반대인 것이 핵심이다 — 인덱스는 선행 컬럼으로만 진입할 수 있으므로
    -- (follower_id, followee_id) 로는 "followee_id = ?" 조회를 처리할 수 없다.
    KEY idx_follows_followee_follower (followee_id, follower_id),

    -- FK 두 개 모두 위 인덱스들이 선행 컬럼을 제공하므로, InnoDB 가 별도 인덱스를 자동 생성하지 않는다.
    CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id),
    CONSTRAINT fk_follows_followee FOREIGN KEY (followee_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- -------------------------------------------------------------------------------------
-- posts
--
-- ★ 조회용 보조 인덱스를 절대 넣지 않는다 ★
--
--   채택 인덱스는 M0(인덱스 없음) 측정과 EXPLAIN ANALYZE 분석의 산출물로
--   Phase 1(P1-13)에서 최초 도입한다(§4.1 주석 · §9.2 · 부록 B).
--   후보는 (author_id, id DESC) 와 (author_id, is_deleted, id DESC) 두 개이고,
--   둘 중 무엇을 쓸지는 실측 비교로 정한다.
--
--   여기서 미리 인덱스를 깔면 M1("Pull + 인덱스")이 M0 대비 무엇을 얼마나 바꿨는지 말할 수 없게 되고,
--   그러면 "인덱스만으로는 한계가 있어서 Push 로 갔다"는 이 프로젝트의 서사 전체가 근거를 잃는다.
--   즉 이건 최적화를 미룬 게 아니라, 최적화의 효과를 측정 가능하게 남겨 둔 것이다.
--
--   다만 FK 제약은 유지한다(로드맵 4.3 — "UNIQUE·FK 제약과 follows 의 인덱스는 유지한다").
--   InnoDB 는 FK 자식 컬럼에 인덱스가 없으면 자동으로 하나 만든다. 그래서 author_id 단일 컬럼
--   인덱스가 fk_posts_author 라는 이름으로 딸려 온다 — 이건 우리가 고른 조회용 인덱스가 아니라
--   FK 제약의 구현 부산물이다. M0 의 "인덱스 없음"은 이 프로젝트에서
--   "Pull 조회를 겨냥한 복합 인덱스가 없다"는 뜻이며, EXPLAIN 해석 시 이 단일 인덱스의 존재를
--   전제로 읽어야 한다(정렬은 여전히 인덱스로 처리되지 않는다).
-- -------------------------------------------------------------------------------------
CREATE TABLE posts
(
    -- AUTO_INCREMENT 가 아니다. Snowflake ID 를 애플리케이션이 생성해 대입한다(마스터 §4.2, 작업 0.5).
    -- 상위 41bit 가 타임스탬프이므로 id 정렬이 곧 시간 정렬이고, 그 덕분에
    -- Redis Sorted Set 의 score 와 커서 페이지네이션의 커서를 id 하나로 통일할 수 있다.
    -- DB 채번 대기가 없어 삽입 병목도 사라진다.
    id         BIGINT       NOT NULL,

    author_id  BIGINT       NOT NULL,
    content    VARCHAR(500) NOT NULL, -- 더미 데이터 기준 평균 80B
    like_count INT          NOT NULL DEFAULT 0, -- 비정규화 카운터. SoT 는 DB(원자적 UPDATE).

    -- soft delete. 하드 삭제는 없다 —
    -- 캐시(타임라인 Sorted Set)에는 post_id 만 들어 있어서 행이 사라지면 되살릴 방법이 없고,
    -- "조회 25개 → 삭제 필터 → 20개 반환"이라는 페이지 규약 자체가 이 플래그를 전제로 한다.
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- -------------------------------------------------------------------------------------
-- likes
-- -------------------------------------------------------------------------------------
CREATE TABLE likes
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- 중복 좋아요 방지. posts 쪽 FK 의 선행 컬럼 인덱스도 겸한다.
    UNIQUE KEY uk_likes_post_user (post_id, user_id),

    CONSTRAINT fk_likes_post FOREIGN KEY (post_id) REFERENCES posts (id),
    -- user_id 는 선행 컬럼으로 삼는 인덱스가 없어 InnoDB 가 fk_likes_user 인덱스를 자동 생성한다.
    -- posts 와 달리 likes 에는 인덱스 제약이 없으므로 그대로 둔다.
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
