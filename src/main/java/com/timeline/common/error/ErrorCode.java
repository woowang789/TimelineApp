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
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	/**
	 * 자기 자신을 대상으로 한 follow/unfollow. 가입 시 삽입되는 self-follow 행(&sect;4.3)은
	 * <strong>시스템 불변식</strong>이라 API로 만들거나 지울 수 없다(부록 B 확정).
	 */
	SELF_FOLLOW_FORBIDDEN(HttpStatus.BAD_REQUEST, "자기 자신은 팔로우할 수 없습니다."),
	DUPLICATE_FOLLOW(HttpStatus.CONFLICT, "이미 팔로우한 사용자입니다."),
	FOLLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "팔로우하지 않은 사용자입니다."),
	/** 없는 게시글과 soft delete된 게시글을 한 코드로 합친다 — 삭제되었다는 사실 자체를 알려 줄 이유가 없다. */
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
	/**
	 * 남의 게시글을 삭제하려는 요청. 404로 뭉개지 않고 403으로 구분한다 —
	 * 게시글은 누구나 조회할 수 있으므로 존재를 감출 이유가 없고, 클라이언트는 재시도해도
	 * 소용없다는 것을 알아야 한다.
	 */
	NOT_POST_AUTHOR(HttpStatus.FORBIDDEN, "본인이 작성한 게시글만 삭제할 수 있습니다."),
	DUPLICATE_LIKE(HttpStatus.CONFLICT, "이미 좋아요한 게시글입니다."),
	LIKE_NOT_FOUND(HttpStatus.NOT_FOUND, "좋아요하지 않은 게시글입니다."),
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
