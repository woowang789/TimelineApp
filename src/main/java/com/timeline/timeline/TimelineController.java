package com.timeline.timeline;

import com.timeline.common.api.CursorPageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 타임라인 API (마스터 &sect;6) — <strong>이 프로젝트의 측정 대상 엔드포인트</strong>다.
 *
 * <p>조회 주체는 경로가 아니라 <strong>토큰</strong>에서 온다. {@code /users/{userId}/timeline} 형태로
 * 열면 남의 타임라인을 들여다볼 수 있게 되고, k6 시나리오도 "누구의 타임라인인가"를 URL로 다루게 되어
 * 코호트 고정(&sect;9.3)이 흐려진다. 내 타임라인은 하나뿐이므로 경로도 하나다.
 */
@RestController
@RequestMapping("/api/v1")
public class TimelineController {

	/** 마스터 &sect;6의 페이지 크기 기본값. 상한도 같은 값이다 — 근거는 {@code TimelineService.PAGE_SIZE}. */
	private static final String DEFAULT_PAGE_SIZE = "20";

	private final TimelineService timelineService;

	public TimelineController(TimelineService timelineService) {
		this.timelineService = timelineService;
	}

	/** 내 타임라인. {@code cursor}가 없으면 첫 페이지(최신순)다. */
	@GetMapping("/timeline")
	public CursorPageResponse<TimelineItem> getTimeline(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
		return timelineService.getTimeline(userId, cursor, size);
	}
}
