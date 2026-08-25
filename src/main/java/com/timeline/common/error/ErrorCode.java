package com.timeline.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드.
 *
 * <p>Phase 0의 0.2 시점에는 최소 항목만 둔다. 도메인 에러(중복 팔로우, 삭제된 게시글 등)는
 * 해당 도메인을 구현하는 작업에서 추가한다.
 */
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}
}
