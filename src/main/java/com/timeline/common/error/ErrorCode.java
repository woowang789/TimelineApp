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
	/**
	 * 로그인 실패. <strong>"없는 사용자"와 "비밀번호 불일치"를 하나의 코드·메시지로 합친 것이 요점이다</strong> —
	 * 구분해서 알려주면 로그인 API가 username 존재 여부를 확인해 주는 도구가 된다.
	 */
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
	/** 위조·만료·Redis 저장값 불일치를 모두 포함한다. 사유를 구분해 봐야 공격자에게만 쓸모가 있다. */
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
	/** 인증 없이 보호 경로에 접근. {@code JwtAuthenticationEntryPoint}가 쓴다. */
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
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
