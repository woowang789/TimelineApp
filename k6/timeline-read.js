/**
 * 타임라인 조회 부하 스크립트 — open model 전용 (마스터 §9.1 / 로드맵 20-phase-1 §4.4)
 *
 * 구조는 마스터 §9.1 그대로다. 다만 **한 번의 실행에 시나리오 하나만** 올린다.
 * §9.1의 코드는 saturation과 slo를 한 options에 같이 선언하는데, 그대로 돌리면
 * slo(startTime 2m)가 saturation 사다리 3~5단계와 겹쳐 주입된다.
 * 두 부하가 섞이면 "이 p99가 어느 주입량에서 나온 값인가"에 답할 수 없어 측정이 오염된다.
 * → `SCENARIO` ENV로 하나를 골라 별도 런으로 돌리고, 결과도 런 단위로 분리 기록한다.
 *
 * 실행 예 (repo 루트에서)
 *   k6 run --env SCENARIO=saturation --env COHORT=heavy \
 *          --env OUT=docs/perf/raw/m0/run1.json k6/timeline-read.js
 *
 * 스모크 런 (P1-08 검증 방법 — rate 10, 1분)
 *   k6 run --env SCENARIO=saturation --env COHORT=light --env RATES=10 k6/timeline-read.js
 *
 * ── JWT TTL 사전 확인 (마스터 §9.3 / 로드맵 §6 리스크) ────────────────────────────
 * Access TTL은 30분(application.yml `jwt.access-token-ttl`)이다. 토큰은 setup()에서 발급되고
 * 런의 최대 길이는 slo = startTime 2m + duration 5m + gracefulStop 30s ≈ 7.5분,
 * saturation = 5단계 × 1m + gracefulStop 30s ≈ 5.5분이다. 30분 ≫ 7.5분이므로
 * 런 도중 토큰이 만료되어 401이 http_req_failed로 새어 들어갈 여지가 없다.
 * (RATES/DURATION을 늘려 런이 25분을 넘기게 만들면 이 전제가 깨진다.)
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';

// setup()의 로그인 요청이 섞이지 않은 타임라인 전용 하위 메트릭 이름.
const TIMELINE_DURATION = 'http_req_duration{endpoint:timeline}';
const TIMELINE_FAILED = 'http_req_failed{endpoint:timeline}';

// ── ENV 인터페이스 ────────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = (__ENV.SCENARIO || 'saturation').toLowerCase();
const COHORT = (__ENV.COHORT || 'normal').toLowerCase();
const OUT = __ENV.OUT || '';

// 더미 사용자는 전원 같은 평문 비밀번호를 쓴다 (BCrypt 해시 1개 재사용 — 로드맵 P1-01/§4.2).
// 로컬 더미 전용 값이라 저장소에 그대로 둔다.
const PASSWORD = 'password123';

// saturation 사다리. M0/M1은 예상 처리량이 낮아 §9.1의 100→3,000을 그대로 주입하면
// 전 구간 dropped_iterations>0으로 측정 자체가 무효가 된다.
// 마스터 §9.1의 "구조는 동일하게 두고 수치만 하향" 규정에 따른 하향 사다리다.
const DEFAULT_RATES = [10, 25, 50, 100, 250];
const STAGE_DURATION = '1m';

const RATES = parseRates(__ENV.RATES, DEFAULT_RATES);

// §9.1 기본값을 시나리오별로 유지한다 (saturation 200/3000, slo 400/2000).
// ENV가 주어지면 그것이 이긴다 — 로드맵 §6 리스크의 "maxVUs 상향(호스트 메모리 한도 내)" 대응.
const PRE_ALLOCATED_VUS = intEnv('PRE_ALLOCATED_VUS', SCENARIO === 'slo' ? 400 : 200);
const MAX_VUS = intEnv('MAX_VUS', SCENARIO === 'slo' ? 2000 : 3000);

const SLO_RATE = intEnv('RATE', 1000);
const SLO_DURATION = __ENV.DURATION || '5m';
// JVM 워밍업 이후 계측 (§9.3). 고정값이므로 ENV로 열지 않는다.
// 주의: 시나리오를 분리 실행하면 이 2분 동안 아무 부하도 들어가지 않는다 —
// §9.1처럼 saturation과 동시 실행할 때만 "워밍업 후"가 된다.
// 분리 실행에서는 이 앞에 saturation 런을 먼저 돌려 JVM을 데운 상태로 이어서 실행한다(P1-09 순서).
const SLO_START_TIME = '2m';

// ── 코호트 로딩 ───────────────────────────────────────────────────────────────────
// open()은 init 컨텍스트에서만 호출할 수 있고 경로는 **스크립트 파일 기준**이다(CWD와 무관).
// 파일이 아직 없어도 init에서 터지지 않게 감싼다 — 그래야 `k6 inspect`로 구조 검증이 가능하고,
// 진짜 실패는 setup()에서 사람이 읽을 수 있는 문장으로 낸다.
let COHORT_USERS = [];
let COHORT_ERROR = null;
try {
	const parsed = JSON.parse(open('./data/cohorts.json'));
	COHORT_USERS = parsed[COHORT];
	if (!Array.isArray(COHORT_USERS) || COHORT_USERS.length === 0) {
		COHORT_ERROR =
			`k6/data/cohorts.json에 '${COHORT}' 코호트가 없거나 비어 있다. ` +
			`(가능한 키: ${Object.keys(parsed).join(', ')})`;
		COHORT_USERS = [];
	}
} catch (e) {
	COHORT_ERROR = `k6/data/cohorts.json을 읽지 못했다: ${e.message} — make seed로 코호트를 export했는지 확인한다.`;
}

// ── options ──────────────────────────────────────────────────────────────────────
export const options = {
	scenarios: buildScenarios(),
	thresholds: {
		// M0/M1에서 이 threshold는 **실패가 예상된다**. 그게 baseline의 결과다(로드맵 §4.4).
		// 따라서 abortOnFail을 걸지 않는다 — 중단시키면 포화점을 못 찾는다.
		http_req_duration: ['p(99)<200'],
		http_req_failed: ['rate<0.01'],

		// ── setup() 오염 차단 ──────────────────────────────────────────────────
		// k6는 setup()에서 보낸 요청도 http_req_* 메트릭에 그대로 집계한다(실측 확인).
		// 코호트 2,000명 로그인은 BCrypt 때문에 건당 수십~수백 ms라, 전체의 2~3%에 불과해도
		// **가장 느린 표본**으로 p99 구간을 통째로 차지한다. 그러면 p99가 타임라인 지연이 아니라
		// 로그인 지연이 되어, §9.7 표에 적을 숫자가 틀린다.
		// → 타임라인 요청에만 붙인 endpoint 태그로 하위 메트릭을 만들고, 보고 수치는 이쪽을 쓴다.
		//   (threshold를 걸어야 하위 메트릭이 summary JSON에 실린다 — §9.6 원시 결과의 근거값)
		'http_req_duration{endpoint:timeline}': ['p(99)<200'],
		'http_req_failed{endpoint:timeline}': ['rate<0.01'],
		// 주입량을 못 채우면 측정 무효 (§9.1).
		// 단, 이 게이트의 의미는 시나리오마다 다르다. 시나리오를 분리 실행하므로 러너가 나눠 읽는다:
		//   saturation → count==0 위반이면 그 런은 폐기·재실행 (유효성 게이트)
		//   slo        → M0/M1에서 1,000 RPS 미성립이 예상되고, "주입 불가"라는 사실 자체가 결과다.
		//                이 런은 폐기 대상이 아니라 기록 대상이다(로드맵 §4.4 표).
		dropped_iterations: ['count==0'],

		// ── 스테이지별 분해 (M0 heavy 본 측정 1차에서 발견한 문제의 해법) ──────────
		// 사다리 후반이 지속 상한을 넘으면 대기열이 무한 성장해 60초(k6 타임아웃) 표본이
		// 집계 p50/p99를 지배한다 — 그 집계치로는 "포화점이 어디인가"에 답할 수 없다.
		// 요청마다 현재 스테이지 번호를 태그로 달고(아래 default()), threshold를 걸어
		// 하위 메트릭이 summary JSON에 실리게 한다 → 스테이지(=주입 RPS)별 p50/p99가
		// 별도 시계열 출력 없이 raw summary만으로 복원된다.
		...stageThresholds(),
	},
	// setup()에서 코호트 전원(최대 2,000명) 로그인한다. BCrypt가 1건당 수십~수백 ms라 넉넉히 잡는다.
	setupTimeout: '300s',
	// http.batch 병렬도. 기본 20/6이면 2,000건 로그인이 호스트당 6동시로 직렬화된다(로드맵 §6 리스크).
	batch: 20,
	batchPerHost: 20,
	// p(99)는 k6 기본 summaryTrendStats에 없다. threshold와 §9.7 템플릿(p50/p99)이 모두 필요로 한다.
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ── setup: JWT 사전 발급 ─────────────────────────────────────────────────────────
/**
 * 코호트 전원의 Access 토큰을 미리 받아 배열로 넘긴다.
 *
 * 매 요청 로그인하면 BCrypt가 병목이 되어 타임라인이 아니라 인증 비용을 재게 된다(§9.3).
 *
 * 메모리 주의: setup 반환값은 VU마다 복사된다. 토큰 1개 ≈ 250B 기준
 * 2,000개 ≈ 0.5MB/VU이고, maxVUs가 3,000까지 늘면 1GB를 넘길 수 있다.
 * 호스트가 M1 8GB이므로 dropped_iterations를 못 잡겠다고 MAX_VUS를 무한정 올리지 않는다.
 */
export function setup() {
	if (COHORT_ERROR) {
		fail(COHORT_ERROR);
	}

	const requests = COHORT_USERS.map((u) => ({
		method: 'POST',
		url: `${BASE_URL}/api/v1/auth/login`,
		body: JSON.stringify({ username: u.username, password: PASSWORD }),
		params: {
			headers: { 'Content-Type': 'application/json' },
			tags: { endpoint: 'login' },
		},
	}));

	const responses = http.batch(requests);

	const tokens = [];
	const failures = [];
	for (let i = 0; i < responses.length; i++) {
		const res = responses[i];
		let token = null;
		if (res.status === 200) {
			try {
				token = res.json('accessToken');
			} catch (e) {
				token = null;
			}
		}
		if (token) {
			tokens.push(token);
		} else if (failures.length < 5) {
			failures.push(`${COHORT_USERS[i].username}(status=${res.status})`);
		}
	}

	// 부분 코호트로 재면 "팔로잉 500명 사용자 조회" 같은 시나리오의 전제가 깨진다(§4.1).
	// 하나라도 실패하면 측정을 시작하지 않는다.
	if (tokens.length !== COHORT_USERS.length) {
		fail(
			`코호트 '${COHORT}' 로그인 실패: ${COHORT_USERS.length}명 중 ${tokens.length}명만 발급됨. ` +
				`실패 예시: ${failures.join(', ')}`
		);
	}

	console.log(`setup 완료 — 코호트 '${COHORT}' ${tokens.length}명 JWT 사전 발급 (시나리오: ${SCENARIO})`);
	return tokens;
}

// ── VU 반복 ──────────────────────────────────────────────────────────────────────
/**
 * 코호트에서 사용자 하나를 균등하게 뽑아 첫 페이지를 조회한다.
 * 커서를 주지 않으므로 항상 최신 20건 — 측정 대상은 "커서 없는 첫 페이지"로 고정한다(§4.3).
 */
export default function (tokens) {
	const token = tokens[Math.floor(Math.random() * tokens.length)];

	const res = http.get(`${BASE_URL}/api/v1/timeline`, {
		headers: { Authorization: `Bearer ${token}` },
		// stage 태그: iteration이 **시작된** 시각이 속한 사다리 스테이지 번호(0부터).
		// 주의 — 대기열이 긴 구간에서는 s2에 시작한 요청이 s3에 끝나기도 한다.
		// "그 주입량에서 시작한 요청이 겪은 지연"이 포화점 분석에 맞는 귀속이다.
		tags: { endpoint: 'timeline', stage: currentStage() },
	});

	// 실패율 자체는 http_req_failed가 잡는다. 여기서는 "200인데 내용이 비정상"을 걸러낸다 —
	// 예컨대 인증 통과 후 빈 응답을 빠르게 돌려주는 상태를 성공으로 세면 측정이 거짓말이 된다.
	check(res, {
		'status 200': (r) => r.status === 200,
		'body에 data 필드': (r) => {
			try {
				return r.json('data') != null;
			} catch (e) {
				return false;
			}
		},
	});
}

// ── 요약 출력 ────────────────────────────────────────────────────────────────────
/**
 * OUT이 지정되면 그 경로에 JSON summary를 남긴다(§9.6 — 원시 결과 커밋 대상).
 * 경로는 **k6 프로세스의 CWD 기준**이다. scripts/bench.sh는 repo 루트에서 실행하므로
 * `docs/perf/raw/{m0|m1}/run{n}.json` 규칙이 그대로 성립한다.
 *
 * 재현성을 위해 시나리오·코호트·사다리 파라미터를 summary에 함께 박아 넣는다.
 * 나중에 raw JSON만 보고도 "이건 어떤 주입 조건의 결과였나"를 복원할 수 있어야 한다.
 */
export function handleSummary(data) {
	const meta = runMeta(data);
	const result = {};

	// handleSummary를 정의하면 k6 기본 요약이 출력되지 않는다. 직접 압축 요약을 찍는다.
	result.stdout = renderSummary(data, meta);

	if (OUT) {
		result[OUT] = JSON.stringify(Object.assign({ runMeta: meta }, data), null, 2);
	}
	return result;
}

// ── 헬퍼 ─────────────────────────────────────────────────────────────────────────

function buildScenarios() {
	if (SCENARIO === 'saturation') {
		return {
			// ① 포화점 탐색: RPS를 올리며 p99가 꺾이는 지점을 찾는다
			saturation: {
				executor: 'ramping-arrival-rate',
				// 첫 단계 target과 startRate를 같게 둬 1단계를 "상승"이 아닌 "유지"로 만든다.
				// 최저 주입량에서도 drop이 나는지를 먼저 확인해야 사다리 하향 여부를 판단할 수 있다.
				startRate: RATES[0],
				timeUnit: '1s',
				preAllocatedVUs: PRE_ALLOCATED_VUS,
				maxVUs: MAX_VUS,
				stages: RATES.map((target) => ({ duration: STAGE_DURATION, target: target })),
			},
		};
	}
	if (SCENARIO === 'slo') {
		return {
			// ② SLO 검증: 목표 RPS를 고정 주입한다
			slo: {
				executor: 'constant-arrival-rate',
				rate: SLO_RATE,
				timeUnit: '1s',
				duration: SLO_DURATION,
				preAllocatedVUs: PRE_ALLOCATED_VUS,
				maxVUs: MAX_VUS,
				startTime: SLO_START_TIME,
			},
		};
	}
	throw new Error(`SCENARIO는 saturation 또는 slo여야 한다 (받은 값: '${SCENARIO}')`);
}

/** saturation일 때만 스테이지별 하위 메트릭 threshold를 만든다 (slo는 스테이지 개념이 없다). */
function stageThresholds() {
	if (SCENARIO !== 'saturation') {
		return {};
	}
	const t = {};
	for (let i = 0; i < RATES.length; i++) {
		// 기준값은 상위 threshold와 같은 p(99)<200 — M0/M1에서 실패가 예상되는 기록용이다.
		t[`http_req_duration{endpoint:timeline,stage:s${i}}`] = ['p(99)<200'];
	}
	return t;
}

/** 시나리오 시작 이후 경과 시간으로 현재 사다리 스테이지 번호를 계산한다 (단계 길이 1m 고정). */
function currentStage() {
	if (SCENARIO !== 'saturation') {
		return 'none';
	}
	const elapsedMs = Date.now() - exec.scenario.startTime;
	const idx = Math.min(Math.floor(elapsedMs / 60000), RATES.length - 1);
	return `s${idx}`;
}

function parseRates(raw, fallback) {
	if (!raw) {
		return fallback;
	}
	const parsed = raw.split(',').map((s) => Number(s.trim()));
	if (parsed.length === 0 || parsed.some((n) => !isFinite(n) || n <= 0)) {
		throw new Error(`RATES는 양수 목록이어야 한다 (받은 값: '${raw}')`);
	}
	return parsed;
}

function intEnv(name, fallback) {
	const raw = __ENV[name];
	if (!raw) {
		return fallback;
	}
	const n = Number(raw);
	if (!isFinite(n) || n <= 0) {
		throw new Error(`${name}은 양수여야 한다 (받은 값: '${raw}')`);
	}
	return Math.floor(n);
}

function runMeta(data) {
	return {
		scenario: SCENARIO,
		cohort: COHORT,
		cohortSize: COHORT_USERS.length,
		baseUrl: BASE_URL,
		// saturation 전용
		rates: SCENARIO === 'saturation' ? RATES : null,
		stageDuration: SCENARIO === 'saturation' ? STAGE_DURATION : null,
		// slo 전용
		rate: SCENARIO === 'slo' ? SLO_RATE : null,
		duration: SCENARIO === 'slo' ? SLO_DURATION : null,
		startTime: SCENARIO === 'slo' ? SLO_START_TIME : null,
		// 공통
		preAllocatedVUs: PRE_ALLOCATED_VUS,
		maxVUs: MAX_VUS,
		testRunDurationMs: data.state ? data.state.testRunDurationMs : null,
	};
}

function metricValue(data, metric, key) {
	const m = data.metrics && data.metrics[metric];
	if (!m || !m.values || m.values[key] === undefined) {
		return null;
	}
	return m.values[key];
}

function fmt(v, digits) {
	return v === null ? 'n/a' : Number(v).toFixed(digits === undefined ? 2 : digits);
}

function renderSummary(data, meta) {
	const dropped = metricValue(data, 'dropped_iterations', 'count') || 0;
	const lines = [];

	lines.push('');
	lines.push('══ 타임라인 조회 부하 요약 ═══════════════════════════════════════════');
	lines.push(`  시나리오       : ${meta.scenario}`);
	lines.push(`  코호트         : ${meta.cohort} (${meta.cohortSize}명)`);
	if (meta.scenario === 'saturation') {
		lines.push(`  사다리         : ${meta.rates.join(' → ')} RPS (단계당 ${meta.stageDuration})`);
	} else {
		lines.push(`  고정 주입      : ${meta.rate} RPS · ${meta.duration} · startTime ${meta.startTime}`);
	}
	lines.push(`  VU             : preAllocated ${meta.preAllocatedVUs} / max ${meta.maxVUs}`);
	lines.push('');
	// 아래 4줄이 §9.7 템플릿에 옮겨 적을 수치다. setup() 로그인이 섞이지 않은 타임라인 전용 메트릭이다.
	lines.push('  ── 타임라인 요청 (§9.7에 기록할 값) ──');
	// Trend에는 count가 없다. Rate 메트릭의 passes(실패 건) + fails(성공 건)가 타임라인 요청 총수다.
	const timelineReqs =
		(metricValue(data, TIMELINE_FAILED, 'passes') || 0) + (metricValue(data, TIMELINE_FAILED, 'fails') || 0);
	lines.push(`  요청 수        : ${fmt(timelineReqs, 0)}`);
	lines.push(`  p50 / p99      : ${fmt(metricValue(data, TIMELINE_DURATION, 'med'))} ms / ` +
		`${fmt(metricValue(data, TIMELINE_DURATION, 'p(99)'))} ms`);
	lines.push(`  max            : ${fmt(metricValue(data, TIMELINE_DURATION, 'max'))} ms`);
	lines.push(`  실패율         : ${fmt((metricValue(data, TIMELINE_FAILED, 'rate') || 0) * 100, 3)} %`);
	lines.push(`  check 성공률   : ${fmt((metricValue(data, 'checks', 'rate') || 0) * 100, 3)} %`);
	if (meta.scenario === 'saturation') {
		lines.push('');
		lines.push('  ── 스테이지별 (시작 시각 귀속 · 포화점 판독용) ──');
		for (let i = 0; i < RATES.length; i++) {
			const m = `http_req_duration{endpoint:timeline,stage:s${i}}`;
			lines.push(`  s${i} ${String(RATES[i]).padStart(4)} RPS : ` +
				`p50 ${fmt(metricValue(data, m, 'med'), 0)} ms · ` +
				`p99 ${fmt(metricValue(data, m, 'p(99)'), 0)} ms · ` +
				`max ${fmt(metricValue(data, m, 'max'), 0)} ms`);
		}
	}
	lines.push('');
	// 참고치. setup()의 로그인 요청이 포함되어 있어 지연 지표로 인용하면 안 된다.
	lines.push('  ── 전체 HTTP (setup 로그인 포함 · 참고) ──');
	lines.push(`  요청 수        : ${fmt(metricValue(data, 'http_reqs', 'count'), 0)}`);
	lines.push(`  달성 RPS       : ${fmt(metricValue(data, 'http_reqs', 'rate'), 2)}`);
	lines.push(`  p99            : ${fmt(metricValue(data, 'http_req_duration', 'p(99)'))} ms`);
	lines.push('');
	lines.push(`  dropped_iterations : ${fmt(dropped, 0)}`);
	if (dropped > 0 && meta.scenario === 'saturation') {
		lines.push('    → 주입량 미달. 이 saturation 런은 폐기하고 사다리를 낮춰 재실행한다 (§9.1)');
	} else if (dropped > 0) {
		lines.push('    → 목표 RPS 주입 불가. slo에서는 이 사실 자체가 결과다 (로드맵 §4.4)');
	}
	lines.push('');
	lines.push('  threshold');
	thresholdLines(data).forEach((l) => lines.push(l));
	lines.push('    (p(99)<200 실패는 M0/M1에서 예상된 결과다 — 측정 무효 사유가 아니다)');
	lines.push('══════════════════════════════════════════════════════════════════════');
	lines.push('');

	return lines.join('\n');
}

function thresholdLines(data) {
	const out = [];
	Object.keys(data.metrics || {}).forEach((name) => {
		const thresholds = data.metrics[name].thresholds;
		if (!thresholds) {
			return;
		}
		Object.keys(thresholds).forEach((expr) => {
			out.push(`    ${thresholds[expr].ok ? 'PASS' : 'FAIL'}  ${name}: ${expr}`);
		});
	});
	return out;
}
