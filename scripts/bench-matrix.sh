#!/usr/bin/env bash
#
# 측정 매트릭스 러너 — M0/M1 본 측정 전체를 한 명령으로 재현한다 (완료 조건 "단일 명령 재현" · §9.6)
#
# 사용법: scripts/bench-matrix.sh <m0|m1>
#
# 실제 본 측정(P1-11 / P1-14)에서 실행한 절차의 성문화다:
#   코호트 3종 × saturation 3회 (지점·코호트별 확정 사다리) + slo(heavy) 1회 시도.
#   각 런은 scripts/bench.sh 가 수행한다(스냅샷 복원 → cold 참고치 → 워밍업 → k6 → 히트율).
#
# 런 사이 휴지 3분:
#   팬리스 M1 Air 는 연속 부하에서 서멀 스로틀링으로 뒷 런이 계단식으로 나빠진다(M1 측정 중 실측 —
#   포화 구간이 최대 5~8배 과대평가되어 3런 폐기). 휴지가 조건 동일성(§9.3)을 지킨다.
#   M0 본 측정 당시에는 이 프로토콜이 없었지만, 휴지는 조건을 깨끗하게 만들 뿐이므로 재현에도 적용한다.
#
# 유효성 게이트: 각 saturation 런의 dropped_iterations == 0. 위반 시 그 자리에서 중단한다 —
#   사다리를 낮추거나 호스트 상태(스왑·발열)를 회복시킨 뒤 재실행한다(§9.1).
#
# 사다리 근거: 예비 측정(P1-10)과 무효 런들의 포화점 관찰로 확정한 값. 지점·코호트마다 다르다 —
#   §9.1 "구조 동일, 수치는 예상 포화점에 맞춰 조정" 규정.

set -euo pipefail

SPOT="${1:-}"
case "$SPOT" in
	m0 | m1) ;;
	*)
		echo "사용법: scripts/bench-matrix.sh <m0|m1>" >&2
		exit 2
		;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

RAW_DIR="docs/perf/raw/$SPOT"
REST_SECONDS=180

# 지점·코호트별 확정 파라미터 (본 측정에 실제 사용한 값)
rates_for() {
	case "$SPOT-$1" in
		m0-heavy)  echo "1,2,3,4,6" ;;
		m0-normal) echo "2,5,10,20,40" ;;
		m0-light)  echo "10,25,50,100,150" ;;
		m1-heavy)  echo "2,5,10,15,20" ;;
		m1-normal) echo "5,10,20,40,80" ;;
		m1-light)  echo "10,25,50,100,150" ;;
	esac
}
pre_vus_for() {
	if [ "$SPOT-$1" = "m0-heavy" ]; then echo 400; else echo 600; fi
}

rest() {
	echo "[matrix] 휴지 ${REST_SECONDS}s (서멀 회복)"
	sleep "$REST_SECONDS"
}

dropped_of() {
	python3 -c "import json; print(int(json.load(open('$1'))['metrics'].get('dropped_iterations',{}).get('values',{}).get('count',0)))"
}

for COHORT in heavy normal light; do
	RATES="$(rates_for "$COHORT")"
	PRE="$(pre_vus_for "$COHORT")"
	for N in 1 2 3; do
		echo "════ [matrix] $SPOT $COHORT saturation run$N — RATES=$RATES PRE_VUS=$PRE ════"
		RATES="$RATES" PRE_ALLOCATED_VUS="$PRE" scripts/bench.sh "$SPOT" "$COHORT" "$N" saturation
		for EXT in json cpu.log hitrate.txt jfr app.log meta.txt; do
			[ -f "$RAW_DIR/run$N.$EXT" ] && mv "$RAW_DIR/run$N.$EXT" "$RAW_DIR/$COHORT-run$N.$EXT"
		done
		echo "run 사이 휴지: ${REST_SECONDS}s" >>"$RAW_DIR/$COHORT-run$N.meta.txt"
		D="$(dropped_of "$RAW_DIR/$COHORT-run$N.json")"
		if [ "$D" != "0" ]; then
			echo "[matrix] dropped=$D — 이 런은 무효다. 중단한다 (§9.1: 폐기·재실행)" >&2
			exit 1
		fi
		echo "[matrix] $COHORT run$N 유효 (dropped=0)"
		rest
	done
done

echo "════ [matrix] $SPOT slo (heavy · 1,000 RPS 시도) ════"
scripts/bench.sh "$SPOT" heavy 1 slo
for EXT in json cpu.log hitrate.txt jfr app.log meta.txt; do
	[ -f "$RAW_DIR/run1.$EXT" ] && mv "$RAW_DIR/run1.$EXT" "$RAW_DIR/slo-heavy-run1.$EXT"
done

echo "[matrix] $SPOT 매트릭스 완료 — 결과: $RAW_DIR/{heavy,normal,light}-run{1..3}.json + slo-heavy-run1.json"
