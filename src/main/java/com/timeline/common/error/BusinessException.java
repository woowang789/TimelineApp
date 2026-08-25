package com.timeline.common.error;

/**
 * 도메인 규칙 위반 예외.
 *
 * <p>상태 코드와 기본 메시지는 {@link ErrorCode}가 들고 있으므로, 예외는 어떤 코드인지만 지정한다.
 * 이렇게 두면 도메인 에러가 늘어나도 {@link GlobalExceptionHandler}에 핸들러를 하나씩 추가할 필요가 없다
 * (0.7~0.11에서 로그인·팔로우·좋아요 에러가 이 위에 쌓인다).
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	/** 원인 예외를 함께 남긴다 — UNIQUE 제약 위반처럼 DB가 먼저 잡아낸 경우에 쓴다. */
	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
