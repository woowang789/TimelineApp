#!/usr/bin/env bash
#
# 측정 파이프라인 — 런 1회를 처음부터 끝까지 재현 가능하게 실행한다 (마스터 §9.3 / 로드맵 20-phase-1 §4.5).
#
# 사용법
#   scripts/bench.sh <지점> <코호트> <런번호> <시나리오>
#   예: scripts/bench.sh m0 heavy 1 saturation
#
# 절차
#   1. 스냅샷 복원 (make db-restore SNAP=<지점>)  — 데이터 동일성 (§9.3)
#   2. bench 프로파일 기동 + healthy 대기
#   3. 앱 기동 (호스트 · -Xmx1g · JFR 녹화) + /actuator/health 대기
#   4. cold 참고치 기록 — 복원 직후 buffer pool이 비어 있는 상태의 기동 완료 표시
#   5. k6 실행 + k6/java CPU 5초 폴링 (§9.3 부하 생성기 격리 한계)
#   6. buffer pool 히트율 산출 (§4.6 — 95% 미만이면 게시글 200만 하향 트리거)
#   7. 앱 SIGTERM 종료 · JFR flush 대기 · 산출 파일 목록 출력
#
# 산출물 (§9.6 커밋 대상 — docs/perf/raw/{m0|m1}/ 아래)
#   run{n}.json         k6 JSON summary
#   run{n}.cpu.log      k6/java CPU 샘플링
#   run{n}.hitrate.txt  buffer pool 히트율
#   run{n}.jfr          JFR 녹화
#   run{n}.app.log      앱 stdout/stderr
#   run{n}.meta.txt     실행 조건 (cold 표시 · 버전 · 스냅샷 복원 여부)
#
# 종료 코드
#   0    파이프라인 정상 완료. k6가 99(threshold 위반)로 끝난 경우도 포함한다 —
#        M0/M1에서 p(99)<200 실패는 **예상된 결과**이지 파이프라인 실패가 아니다(로드맵 §4.4).
#   그 외 k6 종료 코드를 그대로 전파한다 (스크립트 오류·setup 실패 등 진짜 실패).
#
# 이 스크립트는 측정의 유효성을 판정하지 않는다. dropped_iterations 게이트는 k6 요약과
# run{n}.json을 사람이 읽고 판단한다 (saturation은 위반 시 폐기·재실행, slo는 미성립 자체가 결과).

set -euo pipefail

# ── 인자 ─────────────────────────────────────────────────────────────────────────
usage() {
	cat <<'EOF'
사용법: scripts/bench.sh <지점> <코호트> <런번호> <시나리오>

  지점       m0 | m1                    측정 지점 (스냅샷 이름 · 결과 디렉토리)
  코호트     heavy | normal | light     k6/data/cohorts.json의 키
  런번호     1 이상의 정수              3회 반복 중 몇 번째인가 (§9.3)
  시나리오   saturation | slo           §9.1 open model 2종

예:
  scripts/bench.sh m0 heavy 1 saturation
  scripts/bench.sh m1 normal 3 slo

ENV로 조정 가능한 것:
  BASE_URL   (기본 http://localhost:8080)   앱 주소
  RATES      saturation 사다리 오버라이드 (쉼표 구분, 예: RATES=5,10,25)
  RATE       slo 주입 RPS 오버라이드
  DURATION   slo 주입 시간 오버라이드
  PRE_ALLOCATED_VUS / MAX_VUS            VU 풀 오버라이드
EOF
}

if [ "$#" -ne 4 ]; then
	usage >&2
	exit 2
fi

SPOT="$1"
COHORT="$2"
RUN_NO="$3"
SCENARIO="$4"

case "$SPOT" in
	m0 | m1) ;;
	*)
		echo "오류: 지점은 m0 또는 m1이어야 한다 (받은 값: '$SPOT')" >&2
		exit 2
		;;
esac

case "$COHORT" in
	heavy | normal | light) ;;
	*)
		echo "오류: 코호트는 heavy/normal/light 중 하나여야 한다 (받은 값: '$COHORT')" >&2
		exit 2
		;;
esac

case "$RUN_NO" in
	'' | *[!0-9]*)
		echo "오류: 런번호는 1 이상의 정수여야 한다 (받은 값: '$RUN_NO')" >&2
		exit 2
		;;
esac
if [ "$RUN_NO" -lt 1 ]; then
	echo "오류: 런번호는 1 이상의 정수여야 한다 (받은 값: '$RUN_NO')" >&2
	exit 2
fi

case "$SCENARIO" in
	saturation | slo) ;;
	*)
		echo "오류: 시나리오는 saturation 또는 slo여야 한다 (받은 값: '$SCENARIO')" >&2
		exit 2
		;;
esac

# ── 경로 ─────────────────────────────────────────────────────────────────────────
# k6의 handleSummary는 OUT 경로를 **k6 프로세스의 CWD 기준**으로 해석하므로 repo 루트로 이동한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${BASE_URL:-http://localhost:8080}"
JAR="build/libs/timeline-0.0.1-SNAPSHOT.jar"
COMPOSE_FILES=(-f compose.yml -f compose.bench.yml)

OUT_DIR="docs/perf/raw/$SPOT"
RUN_PREFIX="$OUT_DIR/run$RUN_NO"
SUMMARY_JSON="$RUN_PREFIX.json"
CPU_LOG="$RUN_PREFIX.cpu.log"
HITRATE_TXT="$RUN_PREFIX.hitrate.txt"
JFR_FILE="$RUN_PREFIX.jfr"
APP_LOG="$RUN_PREFIX.app.log"
META_TXT="$RUN_PREFIX.meta.txt"

# k6는 summary 출력 경로의 상위 디렉토리를 만들어 주지 않는다 (실측 확인 — 없으면 요약을 통째로 잃는다).
mkdir -p "$OUT_DIR"

APP_PID=""
SAMPLER_PID=""
K6_PID=""

log() { printf '\n[bench] %s\n' "$*"; }

# ── 정리 ─────────────────────────────────────────────────────────────────────────
stop_sampler() {
	if [ -n "$SAMPLER_PID" ] && kill -0 "$SAMPLER_PID" 2>/dev/null; then
		kill "$SAMPLER_PID" 2>/dev/null || true
		wait "$SAMPLER_PID" 2>/dev/null || true
	fi
	SAMPLER_PID=""
}

# SIGTERM으로 내린다. JFR은 dumponexit로 정상 종료 시점에 flush되므로 SIGKILL을 먼저 쓰면 녹화를 잃는다.
stop_app() {
	if [ -z "$APP_PID" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
		APP_PID=""
		return 0
	fi
	log "앱 종료 (SIGTERM · JFR flush 대기)"
	kill -TERM "$APP_PID" 2>/dev/null || true

	local waited=0
	while [ "$waited" -lt 90 ] && kill -0 "$APP_PID" 2>/dev/null; do
		sleep 2
		waited=$((waited + 2))
	done

	if kill -0 "$APP_PID" 2>/dev/null; then
		echo "경고: 90초 안에 종료되지 않아 SIGKILL한다 — JFR 녹화가 잘렸을 수 있다" >&2
		kill -KILL "$APP_PID" 2>/dev/null || true
	fi
	wait "$APP_PID" 2>/dev/null || true
	APP_PID=""
}

cleanup() {
	# k6는 백그라운드(별도 프로세스 그룹이 아닌 잡)로 띄우므로 Ctrl-C가 직접 전달되지 않는다.
	# 여기서 명시적으로 내리지 않으면 스크립트가 죽은 뒤에도 부하 생성기만 남아 DB를 계속 때린다.
	if [ -n "$K6_PID" ] && kill -0 "$K6_PID" 2>/dev/null; then
		kill -TERM "$K6_PID" 2>/dev/null || true
		wait "$K6_PID" 2>/dev/null || true
	fi
	stop_sampler
	stop_app
}
trap cleanup EXIT
trap 'echo; echo "[bench] 중단 신호 — 정리 후 종료한다" >&2; exit 130' INT TERM

# ── 1. 스냅샷 복원 ───────────────────────────────────────────────────────────────
# Makefile의 db-restore는 P1-05 산출물이다. 아직 없을 수 있으므로(리허설 편의) 경고 후 진행한다 —
# 다만 스냅샷을 복원하지 않은 런은 "매 측정 전 볼륨 복원"(§9.3) 조건을 못 지킨 것이므로
# 본 측정 수치로 쓰면 안 된다. 그 사실을 meta에 남긴다.
SNAPSHOT_RESTORED="no"
log "1) 스냅샷 복원 — make db-restore SNAP=$SPOT"
if [ -f Makefile ] && make -n db-restore SNAP="$SPOT" >/dev/null 2>&1; then
	make db-restore SNAP="$SPOT"
	SNAPSHOT_RESTORED="yes"
else
	echo "경고: make db-restore 타깃을 찾지 못했다 — 스냅샷 복원 생략(리허설 모드)." >&2
	echo "      이 런은 데이터 동일성 조건(§9.3)을 만족하지 않으므로 본 측정으로 쓰지 않는다." >&2
fi

# ── 2. bench 프로파일 기동 ───────────────────────────────────────────────────────
log "2) bench 프로파일 기동 (compose.yml + compose.bench.yml)"
docker compose "${COMPOSE_FILES[@]}" up -d

wait_for_healthy() {
	local service="$1" timeout="${2:-240}" waited=0 cid status
	while [ "$waited" -lt "$timeout" ]; do
		cid="$(docker compose "${COMPOSE_FILES[@]}" ps -q "$service" 2>/dev/null || true)"
		if [ -n "$cid" ]; then
			status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none)"
			if [ "$status" = "healthy" ]; then
				return 0
			fi
			if [ "$status" = "none" ]; then
				echo "경고: $service 에 healthcheck가 없다 — 기동 확인을 건너뛴다" >&2
				return 0
			fi
		fi
		sleep 3
		waited=$((waited + 3))
	done
	echo "오류: $service 가 ${timeout}초 안에 healthy가 되지 않았다" >&2
	return 1
}

# prometheus/grafana에는 healthcheck가 없다. 측정 경로에 있는 두 컨테이너만 확인한다.
wait_for_healthy mysql
wait_for_healthy redis
log "   mysql/redis healthy"

# ── 3. 앱 기동 ───────────────────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
	log "3a) $JAR 가 없다 — bootJar 선행"
	./gradlew bootJar
fi

log "3b) 앱 기동 (호스트 · -Xmx1g · JFR → $JFR_FILE)"
# duration=0 = 무제한 녹화, dumponexit=true = 종료 시 파일로 flush.
# settings는 기본(default) 프로파일이다 — 오버헤드가 낮아 측정 자체를 덜 흔든다.
# 더 촘촘한 플레임그래프가 필요하면 settings=profile로 바꾸되, 그 런은 조건이 달라졌음을 리포트에 적는다.
java -Xmx1g \
	-XX:StartFlightRecording=duration=0,dumponexit=true,filename="$JFR_FILE" \
	-jar "$JAR" >"$APP_LOG" 2>&1 &
APP_PID=$!

wait_for_app() {
	local timeout=240 waited=0
	while [ "$waited" -lt "$timeout" ]; do
		if curl -fsS -o /dev/null "$BASE_URL/actuator/health"; then
			return 0
		fi
		if ! kill -0 "$APP_PID" 2>/dev/null; then
			echo "오류: 앱 프로세스가 기동 중 종료됐다 — $APP_LOG 확인" >&2
			return 1
		fi
		sleep 3
		waited=$((waited + 3))
	done
	echo "오류: /actuator/health 가 ${timeout}초 안에 200을 주지 않았다 — $APP_LOG 확인" >&2
	return 1
}
wait_for_app
APP_READY_AT="$(date '+%Y-%m-%d %H:%M:%S %z')"
log "   앱 기동 완료 (pid=$APP_PID)"

# ── 4. cold 참고치 + 실행 조건 기록 ──────────────────────────────────────────────
# Phase 1에는 Redis 타임라인 경로가 없어 cold/warm은 buffer pool 기준이다(로드맵 §4.5).
# 복원 직후 기동한 이 시점이 buffer pool cold 상태다 — 여기부터 k6가 데우기 시작한다.
log "4) cold 참고치 기록 → $META_TXT"
{
	echo "# 실행 조건 (scripts/bench.sh)"
	echo "지점              : $SPOT"
	echo "코호트            : $COHORT"
	echo "런번호            : $RUN_NO"
	echo "시나리오          : $SCENARIO"
	echo "시작 시각         : $(date '+%Y-%m-%d %H:%M:%S %z')"
	echo "스냅샷 복원       : $SNAPSHOT_RESTORED"
	echo "앱 기동 완료      : $APP_READY_AT  (buffer pool cold — 이 시점부터 k6가 데운다)"
	echo "BASE_URL          : $BASE_URL"
	echo "RATES(ENV)        : ${RATES:-미지정(스크립트 기본 사다리)}"
	echo "RATE(ENV)         : ${RATE:-미지정}"
	echo "DURATION(ENV)     : ${DURATION:-미지정}"
	echo "PRE_ALLOCATED_VUS : ${PRE_ALLOCATED_VUS:-미지정}"
	echo "MAX_VUS           : ${MAX_VUS:-미지정}"
	echo
	echo "# 도구 버전"
	echo "k6                : $(k6 version 2>/dev/null | head -1)"
	echo "java              : $(java -version 2>&1 | head -1)"
	echo "docker compose    : $(docker compose version --short 2>/dev/null)"
	echo "host              : $(uname -sm) / $(sysctl -n hw.ncpu 2>/dev/null || echo '?')코어"
} >"$META_TXT"

# ── buffer pool 상태 읽기 ────────────────────────────────────────────────────────
mysql_bp_status() {
	docker compose "${COMPOSE_FILES[@]}" exec -T -e MYSQL_PWD=root mysql \
		mysql -uroot -N -B -e "SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%'" 2>/dev/null
}

bp_value() {
	printf '%s\n' "$1" | awk -F'\t' -v k="$2" '$1==k {print $2}'
}

BP_BEFORE="$(mysql_bp_status || true)"
BP_READS_BEFORE="$(bp_value "$BP_BEFORE" Innodb_buffer_pool_reads)"
BP_REQS_BEFORE="$(bp_value "$BP_BEFORE" Innodb_buffer_pool_read_requests)"

# ── 5. k6 실행 + CPU 샘플링 ──────────────────────────────────────────────────────
# ps의 %cpu는 macOS에서 "최근 1분에 걸친 감쇠 평균"이다. 순간값이 아니므로 스파이크를 정확히
# 짚지는 못하지만, k6와 앱이 8코어를 나눠 쓰는 경합 구간을 판단하기에는 충분하다(§9.3).
sample_cpu() {
	local out="$1" java_pid="$2" k6_pid="$3"
	local ts jcpu kcpu
	printf 'timestamp\tjava_pid\tjava_cpu\tk6_pid\tk6_cpu\n' >"$out"
	while kill -0 "$k6_pid" 2>/dev/null; do
		ts="$(date '+%H:%M:%S')"
		jcpu="$(ps -o %cpu= -p "$java_pid" 2>/dev/null | tr -d ' ')"
		kcpu="$(ps -o %cpu= -p "$k6_pid" 2>/dev/null | tr -d ' ')"
		printf '%s\t%s\t%s\t%s\t%s\n' "$ts" "$java_pid" "${jcpu:-NA}" "$k6_pid" "${kcpu:-NA}" >>"$out"
		sleep 5
	done
}

log "5) k6 실행 — SCENARIO=$SCENARIO COHORT=$COHORT → $SUMMARY_JSON"
if [ "$SCENARIO" = "slo" ]; then
	echo "    참고: slo의 startTime 2m은 §9.3의 '워밍업 이후 계측' 지연이다."
	echo "          시나리오를 분리 실행하므로 이 2분 동안 부하가 없다 —"
	echo "          JVM을 실제로 데우려면 이 런 앞에 saturation 런을 먼저 돌린다(P1-09 순서)."
fi

# RATES / RATE / DURATION / PRE_ALLOCATED_VUS / MAX_VUS는 따로 넘기지 않는다 —
# k6 run은 --include-system-env-vars가 기본 true라 호출자가 준 환경변수가 __ENV로 그대로 들어간다.
BASE_URL="$BASE_URL" k6 run \
	--env SCENARIO="$SCENARIO" \
	--env COHORT="$COHORT" \
	--env OUT="$SUMMARY_JSON" \
	k6/timeline-read.js &
K6_PID=$!

sample_cpu "$CPU_LOG" "$APP_PID" "$K6_PID" &
SAMPLER_PID=$!

set +e
wait "$K6_PID"
K6_EXIT=$?
set -e
K6_PID=""

stop_sampler
log "   k6 종료 (exit=$K6_EXIT)"

# ── 6. buffer pool 히트율 ────────────────────────────────────────────────────────
# SHOW GLOBAL STATUS는 서버 기동 이후 **누적**이다. 누적치만 보면 기동·워밍업 구간의 읽기가 섞여
# 측정 구간의 히트율을 과소평가한다. 그래서 런 전후 두 스냅샷의 차분도 함께 남긴다.
# §4.6의 95% 게이트는 "측정 중" 히트율이므로 차분 쪽이 판정 근거다.
log "6) buffer pool 히트율 산출 → $HITRATE_TXT"
BP_AFTER="$(mysql_bp_status || true)"
BP_READS_AFTER="$(bp_value "$BP_AFTER" Innodb_buffer_pool_reads)"
BP_REQS_AFTER="$(bp_value "$BP_AFTER" Innodb_buffer_pool_read_requests)"

hit_rate() {
	awk -v r="$1" -v rr="$2" 'BEGIN {
		if (r == "" || rr == "" || rr + 0 <= 0) { print "NA"; exit }
		printf "%.4f", 1 - (r + 0) / (rr + 0)
	}'
}

{
	echo "# buffer pool 히트율 — 1 - (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)"
	echo "# 게이트: 측정 중 히트율 95% 미만이면 게시글 200만 하향 트리거 (마스터 §8 / 로드맵 §4.6)"
	echo
	echo "[런 전 · 서버 기동 이후 누적]"
	echo "  Innodb_buffer_pool_reads          = ${BP_READS_BEFORE:-NA}"
	echo "  Innodb_buffer_pool_read_requests  = ${BP_REQS_BEFORE:-NA}"
	echo "  hit_rate                          = $(hit_rate "$BP_READS_BEFORE" "$BP_REQS_BEFORE")"
	echo
	echo "[런 후 · 서버 기동 이후 누적]"
	echo "  Innodb_buffer_pool_reads          = ${BP_READS_AFTER:-NA}"
	echo "  Innodb_buffer_pool_read_requests  = ${BP_REQS_AFTER:-NA}"
	echo "  hit_rate                          = $(hit_rate "$BP_READS_AFTER" "$BP_REQS_AFTER")"
	echo
	echo "[측정 구간 차분 — 95% 게이트의 판정 근거]"
	if [ -n "${BP_READS_BEFORE:-}" ] && [ -n "${BP_READS_AFTER:-}" ]; then
		D_READS=$((BP_READS_AFTER - BP_READS_BEFORE))
		D_REQS=$((BP_REQS_AFTER - BP_REQS_BEFORE))
		echo "  delta reads                       = $D_READS"
		echo "  delta read_requests               = $D_REQS"
		echo "  hit_rate                          = $(hit_rate "$D_READS" "$D_REQS")"
	else
		echo "  NA — mysql 상태 변수를 읽지 못했다"
	fi
	echo
	echo "(SHOW GLOBAL STATUS는 서버 기동 이후 누적이다. 누적치에는 기동·복원 직후의 cold 읽기가"
	echo " 섞여 있으므로, 측정 구간의 히트율은 차분으로 본다.)"
} >"$HITRATE_TXT"

cat "$HITRATE_TXT"

# ── 7. 앱 종료 + 산출 파일 ───────────────────────────────────────────────────────
log "7) 마무리"
stop_app

{
	echo
	echo "종료 시각         : $(date '+%Y-%m-%d %H:%M:%S %z')"
	echo "k6 종료 코드      : $K6_EXIT"
} >>"$META_TXT"

echo
echo "── 산출 파일 ──────────────────────────────────────────────────────────"
for f in "$SUMMARY_JSON" "$CPU_LOG" "$HITRATE_TXT" "$JFR_FILE" "$APP_LOG" "$META_TXT"; do
	if [ -f "$f" ]; then
		printf '  %-40s %s\n' "$f" "$(wc -c <"$f" | tr -d ' ')B"
	else
		printf '  %-40s (없음)\n' "$f"
	fi
done
echo "───────────────────────────────────────────────────────────────────────"
echo "k6 종료 코드: $K6_EXIT"

case "$K6_EXIT" in
	0)
		echo "→ threshold 전부 통과."
		exit 0
		;;
	99)
		echo "→ threshold 위반(99). M0/M1에서 p(99)<200 실패는 예상된 결과다 — 런 자체는 유효할 수 있다."
		echo "  유효성은 $SUMMARY_JSON 의 dropped_iterations로 판정한다."
		exit 0
		;;
	*)
		echo "→ k6가 비정상 종료했다. 이 런은 폐기한다." >&2
		exit "$K6_EXIT"
		;;
esac
