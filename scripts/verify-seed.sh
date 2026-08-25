#!/usr/bin/env bash
#
# 시드 데이터 검증 실행기 (P1-01~P1-05).
#
#   scripts/verify-seed.sh          # 풀 스케일 (기본)
#   scripts/verify-seed.sh smoke    # 축소 스케일
#
# verify-seed.sql 은 기본값이 풀 스케일이라, 축소 스케일에서는 앞에 SET 문을 덧붙여 기대값을 덮어쓴다.
# 값은 생성기의 SeedSpec.smoke() 와 한 쌍이다 — 한쪽만 바꾸면 검증이 조용히 통과한다.
#
# 종료 코드: FAIL 이 하나라도 있으면 1.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SCALE="${1:-full}"

case "$SCALE" in
	full)
		PRELUDE=""
		;;
	smoke)
		PRELUDE="
			SET @expect_users = 1000;
			SET @expect_follows_real = 30000;
			SET @expect_posts = 30000;
			SET @tier_s_n = 2;   SET @tier_s_total = 1800;
			SET @tier_a_n = 10;  SET @tier_a_total = 5000;
			SET @tier_b_n = 50;  SET @tier_b_total = 5000;
			SET @tier_c_total = 18200;
			SET @heavy_n = 10;   SET @normal_n = 20;   SET @light_n = 20;
		"
		;;
	*)
		echo "사용법: $0 [full|smoke]" >&2
		exit 2
		;;
esac

echo "== 시드 검증 (스케일: $SCALE) =="

OUTPUT="$( { printf '%s\n' "$PRELUDE"; cat scripts/verify-seed.sql; } \
	| docker compose exec -T mysql mysql -uroot -proot --table timeline 2>&1 \
	| grep -v 'Using a password on the command line' )"

echo "$OUTPUT"

if grep -q 'FAIL' <<<"$OUTPUT"; then
	echo
	echo "검증 실패 — 이 데이터로는 측정에 들어가지 않는다." >&2
	exit 1
fi

echo
echo "전 항목 PASS."
