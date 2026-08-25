package com.timeline.common.error;

/**
 * 에러 응답 본문.
 *
 * @param code    {@link ErrorCode} 이름
 * @param message 클라이언트에게 보여줄 메시지
 */
public record ErrorResponse(String code, String message) {

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message);
	}
}
