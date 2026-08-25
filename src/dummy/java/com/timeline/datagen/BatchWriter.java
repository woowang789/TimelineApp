package com.timeline.datagen;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * JDBC Batch Insert 실행 단위 — <b>배치 1,000행 실행 / 10,000행 커밋</b>(로드맵 &sect;4.7).
 *
 * <p>두 단위가 다른 이유:
 * <ul>
 *   <li>배치 1,000행 — 재작성된 multi-row INSERT 한 문장의 크기를 정한다. 너무 크면
 *       {@code max_allowed_packet}에 걸리고 드라이버 쪽 문자열 조립 비용도 커진다</li>
 *   <li>커밋 10,000행 — 트랜잭션 하나가 붙드는 undo/redo 양을 정한다. 매 배치마다 커밋하면
 *       fsync가 배치 수만큼 일어나고, 전부 한 트랜잭션으로 묶으면 undo 로그가 부풀어
 *       2G 컨테이너에서 버퍼 풀을 밀어낸다</li>
 * </ul>
 */
final class BatchWriter implements AutoCloseable {

	private static final int BATCH_ROWS = 1_000;
	private static final int COMMIT_ROWS = 10_000;

	private final Connection conn;
	private final PreparedStatement ps;
	private final Progress progress;

	private int pending;
	private int sinceCommit;

	BatchWriter(Connection conn, String sql, Progress progress) throws SQLException {
		this.conn = conn;
		this.ps = conn.prepareStatement(sql);
		this.progress = progress;
	}

	/** 바인딩 대상. 호출자가 값을 채운 뒤 {@link #addRow()}를 부른다. */
	PreparedStatement statement() {
		return ps;
	}

	void addRow() throws SQLException {
		ps.addBatch();
		if (++pending == BATCH_ROWS) {
			executePending();
		}
	}

	/** 남은 배치를 비우고 커밋한다. 단계 종료 시 반드시 호출한다. */
	void flush() throws SQLException {
		if (pending > 0) {
			executePending();
		}
		if (sinceCommit > 0) {
			conn.commit();
			sinceCommit = 0;
		}
	}

	private void executePending() throws SQLException {
		ps.executeBatch();
		sinceCommit += pending;
		progress.advance(pending);
		pending = 0;
		if (sinceCommit >= COMMIT_ROWS) {
			conn.commit();
			sinceCommit = 0;
		}
	}

	@Override
	public void close() throws SQLException {
		ps.close();
	}
}
