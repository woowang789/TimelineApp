package com.timeline.common.api;

import java.util.List;

/**
 * 커서 페이지네이션 공통 응답 (마스터 &sect;6).
 *
 * <p>OFFSET은 쓰지 않는다. 커서는 항상 마지막 항목의 ID(Snowflake, {@code Long})이며,
 * 다음 페이지가 없으면 {@code nextCursor}는 {@code null}이다.
 *
 * <pre>
 * { "data": [ ... ], "nextCursor": 9877123456789, "hasNext": true }
 * </pre>
 *
 * @param <T> 페이지 항목 타입
 */
public record CursorPageResponse<T>(List<T> data, Long nextCursor, boolean hasNext) {
}
