package com.timeline.timeline;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Pull 타임라인 조회 — <strong>이 프로젝트가 측정하려는 그 쿼리</strong>다 (마스터 &sect;8 Phase 1).
 *
 * <p><strong>왜 JPA가 아니라 JdbcTemplate인가.</strong> 이유가 셋 있다.
 * <ol>
 *   <li><strong>SQL이 산출물이기 때문이다.</strong> M0/M1의 실체는 이 문장 하나의 실행 계획이고
 *       (P1-12·P1-13의 {@code EXPLAIN ANALYZE} 대상), 리포트에 붙는 SQL과 서버가 실제로 보내는 SQL이
 *       <em>문자 그대로</em> 같아야 그 분석이 코드에 대한 분석이 된다. JPQL은 방언이 SQL을 다시 쓰므로
 *       그 보장이 사라진다. 아래 {@link #PULL_TIMELINE_SQL}은 마스터 &sect;8의 블록과 한 글자도 다르지 않다
 *       (named parameter 표기 {@code :userId}/{@code :cursor}를 살리려고
 *       {@link NamedParameterJdbcTemplate}를 쓴다 — 평범한 {@code JdbcTemplate}이면 {@code ?}가 되어
 *       그 지점에서 문서와 어긋난다).</li>
 *   <li><strong>경계 때문이다.</strong> Spring Data JPA의 native query도 결국 {@code @Entity}에 묶인
 *       리포지터리가 필요한데, 여기서 쓸 수 있는 엔티티는 {@code Post}/{@code Follow}뿐이고 그건
 *       경계 규칙 1 위반이다(패키지 주석 참조). JdbcTemplate은 엔티티 없이 성립하므로
 *       "timeline은 두 테이블을 <em>읽기만</em> 한다"가 import 목록으로 증명된다.</li>
 *   <li><strong>측정 잡음이 없다.</strong> 영속성 컨텍스트에 25건을 적재했다가 버리는 비용도,
 *       더티 체킹도 이 경로에는 없다. 측정 대상이 JOIN과 정렬이지 하이버네이트가 아니다.</li>
 * </ol>
 */
@Component
class TimelinePullQuery {

	/**
	 * 마스터 &sect;8 Phase 1의 SQL <strong>그대로</strong>. 손대지 말 것 — 바꾸면 리포트의 실행 계획이
	 * 다른 문장의 것이 된다.
	 *
	 * <p>{@code LIMIT 25}가 리터럴인 것도 의도다. 응답은 20건이고 25는 "25개 조회 → 삭제 필터 →
	 * 20개 반환"이라는 페이지 규격(&sect;5-2)에서 온 <strong>고정 상수</strong>다 —
	 * Phase 2a의 Redis 경로가 삭제분을 애플리케이션에서 걸러 내야 해서 여유분 5를 두는 것이고,
	 * Pull은 SQL이 이미 걸러 주지만 두 경로의 응답 계약을 지금부터 같게 맞춘다(로드맵 4.3절).
	 *
	 * <p>{@code SELECT p.*}도 그대로 둔다. 필요한 컬럼만 고르면 조회가 가벼워지지만, 그러면
	 * 이 쿼리가 마스터의 그 쿼리가 아니게 된다. 최적화는 M0 분석 <em>다음</em>의 일이다.
	 */
	private static final String PULL_TIMELINE_SQL = """
			SELECT p.* FROM posts p
			JOIN follows f ON p.author_id = f.followee_id
			WHERE f.follower_id = :userId
			  AND p.id < :cursor
			  AND p.is_deleted = false
			ORDER BY p.id DESC LIMIT 25""";

	private static final RowMapper<TimelineItem> ROW_MAPPER = (rs, rowNum) -> new TimelineItem(
			rs.getLong("id"),
			rs.getLong("author_id"),
			rs.getString("content"),
			rs.getInt("like_count"),
			rs.getObject("created_at", LocalDateTime.class));

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * DB 조회 구간 타이머 (P1-07 · &sect;9.3).
	 *
	 * <p>{@code timeline.request}에서 이 값을 빼면 애플리케이션이 쓴 시간이 남는다. 포화 구간에서
	 * 이 타이머만 치솟으면 병목은 DB이고, 이 타이머는 그대로인데 바깥이 커지면 병목은 큐잉이다 —
	 * M0 리포트의 "원인 분석"이 그 대비 위에 서 있다.
	 */
	private final Timer queryTimer;

	TimelinePullQuery(NamedParameterJdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
		this.jdbcTemplate = jdbcTemplate;
		// 히스토그램은 이 타이머에만 켠다. 전역 설정(management.metrics.distribution.*)으로 켜면
		// http.server.requests를 포함한 모든 타이머에 버킷 시계열이 붙어 스크레이프 비용이 불어나고,
		// 그 비용은 측정 중인 프로세스가 문다.
		this.queryTimer = Timer.builder("timeline.pull.query")
				.description("Pull 타임라인 SQL 1회 실행 + 결과 매핑 시간")
				.publishPercentileHistogram()
				.register(meterRegistry);
	}

	/**
	 * 커서 이전의 타임라인 게시글을 최신순으로 최대 25건 읽는다.
	 *
	 * <p>self-follow 행({@code follower_id = followee_id}) 덕분에 <strong>내 글도 이 JOIN에 걸린다</strong>
	 * (&sect;4.3). UNION을 붙이지 않는 이유가 그것이고, 단일 JOIN을 유지해야 실행 계획 분석이 깨끗하다.
	 *
	 * @param cursor 이 값보다 <strong>작은</strong> id부터 읽는다(exclusive).
	 *               첫 페이지는 {@link Long#MAX_VALUE} — 술어를 {@code id < ?} 하나로 고정하기 위함이다
	 */
	List<TimelineItem> findBefore(Long userId, long cursor) {
		Map<String, Object> params = Map.of("userId", userId, "cursor", cursor);
		return queryTimer.record(() -> jdbcTemplate.query(PULL_TIMELINE_SQL, params, ROW_MAPPER));
	}
}
