-- =====================================================================================
-- 시드 데이터 검증 (Phase 1 · P1-01~P1-05의 "검증:" 열)
--
-- 실행:  make verify-seed              (풀 스케일)
--        scripts/verify-seed.sh smoke  (축소 스케일)
--
-- 각 항목이 PASS / FAIL 을 출력한다. 하나라도 FAIL 이면 그 데이터로는 측정에 들어가지 않는다 —
-- 분포가 틀린 데이터 위에서 잰 p99 는 아무것도 증명하지 못한다.
--
-- 구조: 앞부분에서 집계를 전부 사용자 변수에 담고, 마지막 한 문장이 결과표를 만든다.
-- posts 300만 행 전수 스캔이 여러 번 일어나지 않게 하려는 것이다(보조 인덱스가 없는 게 M0 의 전제다).
-- =====================================================================================

-- created_at 은 UTC 벽시계로 저장돼 있다(생성기 SeedTime 참조).
-- 세션 시간대를 UTC 로 고정하지 않으면 UNIX_TIMESTAMP() 가 로컬 시간대로 해석해
-- "id 의 상위 41bit == created_at" 검증이 시간대 차이만큼 통째로 어긋난다.
SET time_zone = '+00:00';

-- Snowflake 커스텀 epoch — 2025-01-01T00:00:00Z (마스터 부록 B, 불변).
SET @epoch = 1735689600000;

-- -------------------------------------------------------------------------------------
-- 스케일 파라미터. 기본값은 풀 스케일(마스터 §8 Phase 1 분포표)이다.
-- 축소 스케일은 이 파일 앞에 SET 문을 덧붙여 덮어쓴다 — verify-seed.sh 가 그 일을 한다.
-- -------------------------------------------------------------------------------------
SET @expect_users        = IFNULL(@expect_users, 100000);
SET @expect_follows_real = IFNULL(@expect_follows_real, 3000000);
SET @expect_posts        = IFNULL(@expect_posts, 3000000);

SET @tier_s_n = IFNULL(@tier_s_n, 10);      SET @tier_s_total = IFNULL(@tier_s_total, 200000);
SET @tier_a_n = IFNULL(@tier_a_n, 100);     SET @tier_a_total = IFNULL(@tier_a_total, 500000);
SET @tier_b_n = IFNULL(@tier_b_n, 1000);    SET @tier_b_total = IFNULL(@tier_b_total, 500000);
SET @tier_c_total = IFNULL(@tier_c_total, 1800000);

-- 코호트 인원. 팔로잉 수(500/100/10)는 스케일과 무관하게 고정이다 — 이 값이 "정확 일치" 게이트의 대상이다.
SET @heavy_n  = IFNULL(@heavy_n, 1000);
SET @normal_n = IFNULL(@normal_n, 2000);
SET @light_n  = IFNULL(@light_n, 2000);
SET @heavy_q = 500;  SET @normal_q = 100;  SET @light_q = 10;

-- 코호트는 사용자 id 구간으로 고정 배정된다(생성기 SeedSpec 주석).
SET @normal_from = @heavy_n + 1;              SET @normal_to = @heavy_n + @normal_n;
SET @light_from  = @normal_to + 1;            SET @light_to  = @normal_to + @light_n;

SET @expect_follows_total = @expect_follows_real + @expect_users;

-- -------------------------------------------------------------------------------------
-- 집계
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) INTO @a_users FROM users;
SELECT COUNT(*) INTO @a_follows FROM follows;
SELECT COUNT(*) INTO @a_self FROM follows WHERE follower_id = followee_id;

-- 계층은 컬럼이 아니라 "팔로워 수 상위 N명"으로 식별한다. 생성기의 계층 배정은 무작위라
-- SQL 이 재현할 수 없지만, S/A/B 의 1인당 팔로워가 C 최대값보다 훨씬 커서 순위가 곧 계층이 된다.
SELECT SUM(CASE WHEN rn <= @tier_s_n THEN c ELSE 0 END),
       SUM(CASE WHEN rn >  @tier_s_n AND rn <= @tier_s_n + @tier_a_n THEN c ELSE 0 END),
       SUM(CASE WHEN rn >  @tier_s_n + @tier_a_n
                 AND rn <= @tier_s_n + @tier_a_n + @tier_b_n THEN c ELSE 0 END),
       SUM(CASE WHEN rn >  @tier_s_n + @tier_a_n + @tier_b_n THEN c ELSE 0 END)
  INTO @a_tier_s, @a_tier_a, @a_tier_b, @a_tier_c
FROM (
    SELECT c, ROW_NUMBER() OVER (ORDER BY c DESC) AS rn
    FROM (
        SELECT followee_id, COUNT(*) AS c
        FROM follows
        WHERE follower_id <> followee_id   -- self-follow 제외 (§4.1)
        GROUP BY followee_id
    ) g
) r;

-- 코호트 팔로잉 수 — 인원과 최소/최대가 모두 기대값과 같아야 한다(평균이 맞는 것으로는 부족하다).
SELECT COUNT(*), MIN(cnt), MAX(cnt) INTO @a_heavy_n, @a_heavy_min, @a_heavy_max
FROM (SELECT follower_id, COUNT(*) cnt FROM follows
      WHERE follower_id <> followee_id AND follower_id BETWEEN 1 AND @heavy_n
      GROUP BY follower_id) t;

SELECT COUNT(*), MIN(cnt), MAX(cnt) INTO @a_normal_n, @a_normal_min, @a_normal_max
FROM (SELECT follower_id, COUNT(*) cnt FROM follows
      WHERE follower_id <> followee_id AND follower_id BETWEEN @normal_from AND @normal_to
      GROUP BY follower_id) t;

SELECT COUNT(*), MIN(cnt), MAX(cnt) INTO @a_light_n, @a_light_min, @a_light_max
FROM (SELECT follower_id, COUNT(*) cnt FROM follows
      WHERE follower_id <> followee_id AND follower_id BETWEEN @light_from AND @light_to
      GROUP BY follower_id) t;

-- follower_count 표본 100명 — id 공간에 고르게 흩뿌려 뽑는다.
SET @fc_step = GREATEST(1, FLOOR(@expect_users / 100));
SELECT COUNT(*), SUM(fc <> real_c) INTO @a_fc_sampled, @a_fc_bad
FROM (
    SELECT u.follower_count AS fc,
           (SELECT COUNT(*) FROM follows f
             WHERE f.followee_id = u.id AND f.follower_id <> u.id) AS real_c
    FROM users u
    WHERE u.id % @fc_step = 0
    LIMIT 100
) t;

-- posts 전수 스캔은 한 번만. 시간 분포 기준점은 MAX(created_at) 이다 —
-- 최근 7일 버킷이 균등이라 최대값이 시드 실행 시각과 사실상 같고, 검증 시각과 무관해진다.
SELECT MAX(created_at) INTO @post_max FROM posts;
SELECT COUNT(*),
       SUM(created_at >  @post_max - INTERVAL 7 DAY),
       SUM(created_at <= @post_max - INTERVAL 7 DAY AND created_at > @post_max - INTERVAL 30 DAY),
       SUM(created_at <= @post_max - INTERVAL 30 DAY),
       AVG(LENGTH(content))
  INTO @a_posts, @a_recent, @a_mid, @a_old, @a_avglen
FROM posts;

-- id 의 상위 41bit 와 created_at 의 일치 — id 공간 양 끝에서 5,000건씩.
SELECT COUNT(*), SUM(ROUND(UNIX_TIMESTAMP(created_at) * 1000) <> (id >> 22) + @epoch)
  INTO @a_id_sampled, @a_id_bad
FROM (
    (SELECT id, created_at FROM posts ORDER BY id ASC LIMIT 5000)
    UNION ALL
    (SELECT id, created_at FROM posts ORDER BY id DESC LIMIT 5000)
) s;

-- -------------------------------------------------------------------------------------
-- 결과표
-- -------------------------------------------------------------------------------------
SELECT 'users.count' AS check_name,
       CAST(@expect_users AS CHAR) AS expected, CAST(@a_users AS CHAR) AS actual,
       IF(@a_users = @expect_users, 'PASS', 'FAIL') AS result
UNION ALL SELECT 'follows.count(real+self)', CAST(@expect_follows_total AS CHAR), CAST(@a_follows AS CHAR),
       IF(@a_follows = @expect_follows_total, 'PASS', 'FAIL')
UNION ALL SELECT 'follows.self_follow', CAST(@expect_users AS CHAR), CAST(@a_self AS CHAR),
       IF(@a_self = @expect_users, 'PASS', 'FAIL')
UNION ALL SELECT 'posts.count', CAST(@expect_posts AS CHAR), CAST(@a_posts AS CHAR),
       IF(@a_posts = @expect_posts, 'PASS', 'FAIL')

-- 계층 팔로워 합계 — 게이트는 ±0.1%(로드맵 §6). 슬롯 풀이 슬롯을 버리지 않으므로 실제로는 정확히 맞는다.
UNION ALL SELECT 'follows.tier_S(±0.1%)', CAST(@tier_s_total AS CHAR), CAST(@a_tier_s AS CHAR),
       IF(ABS(@a_tier_s - @tier_s_total) <= GREATEST(1, @tier_s_total * 0.001), 'PASS', 'FAIL')
UNION ALL SELECT 'follows.tier_A(±0.1%)', CAST(@tier_a_total AS CHAR), CAST(@a_tier_a AS CHAR),
       IF(ABS(@a_tier_a - @tier_a_total) <= GREATEST(1, @tier_a_total * 0.001), 'PASS', 'FAIL')
UNION ALL SELECT 'follows.tier_B(±0.1%)', CAST(@tier_b_total AS CHAR), CAST(@a_tier_b AS CHAR),
       IF(ABS(@a_tier_b - @tier_b_total) <= GREATEST(1, @tier_b_total * 0.001), 'PASS', 'FAIL')
UNION ALL SELECT 'follows.tier_C(±0.1%)', CAST(@tier_c_total AS CHAR), CAST(@a_tier_c AS CHAR),
       IF(ABS(@a_tier_c - @tier_c_total) <= GREATEST(1, @tier_c_total * 0.001), 'PASS', 'FAIL')

-- 코호트 팔로잉 수 — 정확 일치 게이트. min == max == 기대값이고 인원도 같아야 통과다.
UNION ALL SELECT 'cohort.heavy(exact)',
       CONCAT(@heavy_n, ' x ', @heavy_q),
       CONCAT(@a_heavy_n, ' x ', @a_heavy_min, '~', @a_heavy_max),
       IF(@a_heavy_n = @heavy_n AND @a_heavy_min = @heavy_q AND @a_heavy_max = @heavy_q, 'PASS', 'FAIL')
UNION ALL SELECT 'cohort.normal(exact)',
       CONCAT(@normal_n, ' x ', @normal_q),
       CONCAT(@a_normal_n, ' x ', @a_normal_min, '~', @a_normal_max),
       IF(@a_normal_n = @normal_n AND @a_normal_min = @normal_q AND @a_normal_max = @normal_q, 'PASS', 'FAIL')
UNION ALL SELECT 'cohort.light(exact)',
       CONCAT(@light_n, ' x ', @light_q),
       CONCAT(@a_light_n, ' x ', @a_light_min, '~', @a_light_max),
       IF(@a_light_n = @light_n AND @a_light_min = @light_q AND @a_light_max = @light_q, 'PASS', 'FAIL')

UNION ALL SELECT 'users.follower_count(sample)',
       CONCAT(@a_fc_sampled, ' rows, mismatch 0'),
       CONCAT(@a_fc_sampled, ' rows, mismatch ', CAST(IFNULL(@a_fc_bad, 0) AS SIGNED)),
       IF(@a_fc_sampled > 0 AND IFNULL(@a_fc_bad, 0) = 0, 'PASS', 'FAIL')

-- 시간 분포 — 최근 7일 25% / 8~30일 35% / 31~180일 40%, 각 ±1%p
UNION ALL SELECT 'posts.time_7d(25%±1%p)',
       '25.00', CAST(ROUND(100 * @a_recent / @a_posts, 2) AS CHAR),
       IF(ABS(100 * @a_recent / @a_posts - 25) <= 1, 'PASS', 'FAIL')
UNION ALL SELECT 'posts.time_8_30d(35%±1%p)',
       '35.00', CAST(ROUND(100 * @a_mid / @a_posts, 2) AS CHAR),
       IF(ABS(100 * @a_mid / @a_posts - 35) <= 1, 'PASS', 'FAIL')
UNION ALL SELECT 'posts.time_31_180d(40%±1%p)',
       '40.00', CAST(ROUND(100 * @a_old / @a_posts, 2) AS CHAR),
       IF(ABS(100 * @a_old / @a_posts - 40) <= 1, 'PASS', 'FAIL')

UNION ALL SELECT 'posts.avg_length(80B±5)',
       '80', CAST(ROUND(@a_avglen, 2) AS CHAR),
       IF(ABS(@a_avglen - 80) <= 5, 'PASS', 'FAIL')

-- "ID 순서 = 시간 순서" 전제. 어긋나면 커서 페이지네이션과 Redis score 의 의미가 갈라진다(마스터 §4.2).
UNION ALL SELECT 'posts.id_vs_created_at(10k)',
       CONCAT(@a_id_sampled, ' rows, mismatch 0'),
       CONCAT(@a_id_sampled, ' rows, mismatch ', CAST(IFNULL(@a_id_bad, 0) AS SIGNED)),
       IF(@a_id_sampled > 0 AND IFNULL(@a_id_bad, 0) = 0, 'PASS', 'FAIL');
