package com.timeline.timeline;

import com.timeline.common.api.CursorPageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 타임라인 조회 서비스 (P1-06).
 *
 * <p>Phase 1의 경로는 Pull 하나다. Phase 2a에서 Redis 경로가 앞에 붙어도
 * <strong>이 Pull 경로는 폴백으로 남는다</strong>(마스터 &sect;5-7) — 지워지는 코드가 아니다.
 *
 * <p><strong>{@code @Transactional}을 붙이지 않았다.</strong> 조회 SQL 한 문장뿐이라 묶을 것이 없고,
 * 트랜잭션을 열면 요청마다 begin/commit 왕복이 하나씩 더 붙는다. 초당 수백 건을 재는 경로에서
 * 그건 측정 대상이 아닌 비용이다. (readOnly=true도 마찬가지 — 얻는 것은 없고 왕복만 남는다.)
 */
@Service
public class TimelineService {

	/**
	 * 페이지 크기 — <strong>기본값이자 상한이 20</strong>이다 (마스터 &sect;6).
	 *
	 * <p>{@code size}를 20 위로 열지 않는 이유는 조회 SQL의 {@code LIMIT 25}가 고정 상수이기 때문이다.
	 * 25는 "25개 조회 → 삭제 필터 → 20개 반환" 규격에서 온 값이라(&sect;5-2) 페이지 크기를 따라 커지지 않는다.
	 * 21을 요청받아 25건을 읽으면 {@code hasNext} 판정에 쓸 여유가 4건뿐이고, Phase 2a에서 그 4건이
	 * 삭제분에 먹히는 순간 "다음 페이지가 있는데 없다고 답하는" 페이지가 생긴다.
	 * 상한 초과는 400으로 거절하지 않고 20으로 깎는다 — {@code FollowService}의 목록 상한과 같은 규약이다.
	 */
	private static final int PAGE_SIZE = 20;

	/** 커서가 없을 때(첫 페이지) 쓰는 시작점. Snowflake id는 항상 이 값보다 작다. */
	private static final long FIRST_PAGE_CURSOR = Long.MAX_VALUE;

	private final TimelinePullQuery pullQuery;

	/**
	 * 타임라인 요청 전체 구간 타이머 (P1-07 · &sect;9.3).
	 *
	 * <p>계측이 세 겹이라는 점이 중요하다.
	 * <ul>
	 *   <li>{@code http.server.requests{uri="/api/v1/timeline"}} — 액추에이터가 자동으로 붙이는
	 *       서블릿 경계. 필터 체인(JWT 검증)과 JSON 직렬화까지 포함한다</li>
	 *   <li>{@code timeline.request} — 이 타이머. 조회와 페이지 조립만 담는다</li>
	 *   <li>{@code timeline.pull.query} — 그중 SQL 구간</li>
	 * </ul>
	 * 바깥에서 안쪽을 빼면 각 층이 쓴 시간이 나오고, <strong>k6가 잰 클라이언트 지연에서 맨 바깥을 빼면
	 * 요청이 스레드를 기다린 시간(큐잉)</strong>이 남는다. 포화를 "느리다"가 아니라 "어디서 느린가"로
	 * 말하려면 이 분해가 있어야 한다.
	 */
	private final Timer requestTimer;

	public TimelineService(TimelinePullQuery pullQuery, MeterRegistry meterRegistry) {
		this.pullQuery = pullQuery;
		// 히스토그램을 켜는 이유와 전역 설정을 피한 이유는 TimelinePullQuery 생성자 주석과 같다.
		this.requestTimer = Timer.builder("timeline.request")
				.description("타임라인 조회 요청 전체 처리 시간 (DB 조회 + 페이지 조립)")
				.publishPercentileHistogram()
				.register(meterRegistry);
	}

	/**
	 * 타임라인 조회 (Pull).
	 *
	 * <p><strong>{@code hasNext}는 페이지 크기보다 한 건이라도 더 읽혔는지로 판정한다.</strong>
	 * COUNT 쿼리를 따로 던지지 않는 이유는 post·follow 목록과 같다 — 비싸고, 목록과 다른 시점의 답이다.
	 * 다만 여기서는 "한 건 더"가 아니라 <strong>다섯 건 더</strong> 읽힌다(LIMIT 25 vs 20). 남는 5건은
	 * Phase 2a에서 삭제분 필터가 먹을 여유분이고, Phase 1에서는 SQL이 이미 걸러 주므로 그냥 버린다.
	 * 버리는 5건이 곧 두 Phase의 응답 계약을 같게 유지하는 값이다.
	 *
	 * @param cursor 마지막으로 받은 postId. {@code null}이면 첫 페이지(최신부터)
	 * @param size   요청 페이지 크기. 1~{@value #PAGE_SIZE}로 깎인다
	 */
	public CursorPageResponse<TimelineItem> getTimeline(Long userId, Long cursor, int size) {
		int pageSize = Math.clamp(size, 1, PAGE_SIZE);
		long startCursor = cursor != null ? cursor : FIRST_PAGE_CURSOR;

		return requestTimer.record(() -> {
			List<TimelineItem> rows = pullQuery.findBefore(userId, startCursor);

			boolean hasNext = rows.size() > pageSize;
			List<TimelineItem> data = hasNext ? rows.subList(0, pageSize) : rows;
			// 다음 페이지가 없으면 커서도 없다 — 클라이언트가 hasNext를 안 보고 nextCursor만 따라가도 멈춘다.
			Long nextCursor = hasNext ? data.get(data.size() - 1).id() : null;

			return new CursorPageResponse<>(data, nextCursor, hasNext);
		});
	}
}
