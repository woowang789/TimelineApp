package com.timeline.datagen;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 접속 설정. <b>여기가 이 생성기의 성능을 가르는 지점이다.</b>
 *
 * <p>{@code rewriteBatchedStatements=true}가 없으면 Connector/J는 {@code addBatch}를 모아 두고도
 * INSERT를 한 줄씩 왕복시킨다. multi-row INSERT로 재작성되지 않는다는 뜻이고,
 * 600만 행에서는 자릿수 단위로 느려진다(로드맵 &sect;4.7 · &sect;6 "적재 시간 폭주" 리스크의 1순위 점검 항목).
 *
 * <p>{@code useServerPrepStmts=false}를 함께 못 박는다. 서버 프리페어드 문으로 붙으면
 * 재작성 대상이 아니게 되어 위 옵션이 조용히 무력화된다 — 기본값이지만 명시한다.
 */
final class Db {

	private static final String DEFAULT_HOST = "127.0.0.1:3306";
	private static final String DEFAULT_SCHEMA = "timeline";

	static final String DEFAULT_URL = "jdbc:mysql://" + DEFAULT_HOST + "/" + DEFAULT_SCHEMA
			+ "?rewriteBatchedStatements=true"
			+ "&useServerPrepStmts=false"
			+ "&cachePrepStmts=false"
			// created_at은 UTC 문자열 리터럴로 직접 넣는다(SeedTime 참조). 드라이버 쪽 시간대 변환을
			// 끼워 넣지 않으려고 세션 시간대까지 UTC로 고정한다 — "ID 타임스탬프 == created_at" 검증의 전제다.
			+ "&connectionTimeZone=UTC"
			+ "&forceConnectionTimeZoneToSession=true"
			// 로컬 컨테이너 전용. TLS를 끄면 caching_sha2_password 최초 인증에 공개키 조회가 필요하다.
			+ "&sslMode=DISABLED"
			+ "&allowPublicKeyRetrieval=true";

	static final String DEFAULT_USER = "timeline";
	static final String DEFAULT_PASSWORD = "timeline";

	private final String url;
	private final String user;
	private final String password;

	Db(String url, String user, String password) {
		this.url = url;
		this.user = user;
		this.password = password;
	}

	/** autocommit을 끈 커넥션. 배치 실행 단위와 커밋 단위를 {@link BatchWriter}가 따로 통제한다. */
	Connection open() throws SQLException {
		Connection conn = DriverManager.getConnection(url, user, password);
		conn.setAutoCommit(false);
		return conn;
	}

	/** 테이블에 행이 하나라도 있는지. 300만 행을 COUNT하지 않으려고 EXISTS로 묻는다. */
	boolean hasRows(String table) throws SQLException {
		try (Connection conn = open();
				Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT EXISTS(SELECT 1 FROM " + table + ")")) {
			return rs.next() && rs.getBoolean(1);
		}
	}

	/** 이미 적재된 테이블에 덧씌우지 않게 막는다. 중복 적재는 분포 검증을 조용히 깨뜨린다. */
	void requireEmpty(String table) throws SQLException {
		if (hasRows(table)) {
			throw new IllegalStateException(
					table + " 테이블이 비어 있지 않다. 다시 적재하려면 먼저 초기화하라: make db-reset");
		}
	}

	String url() {
		return url;
	}
}
