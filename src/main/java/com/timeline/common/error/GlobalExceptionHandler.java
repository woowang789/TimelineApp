package com.timeline.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리 뼈대.
 *
 * <p>도메인 예외는 {@link BusinessException} 하나로 모으고, 상태 코드는 {@link ErrorCode}에서 읽는다.
 * 그 밖에는 Bean Validation 실패와 미분류 예외를 처리한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 도메인 규칙 위반 — 상태 코드와 메시지는 {@link ErrorCode}가 정한다. */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, errorCode.getMessage()));
	}

	/** {@code @Valid} 검증 실패 — 첫 번째 필드 에러 메시지를 그대로 전달한다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
		FieldError fieldError = e.getBindingResult().getFieldError();
		String message = (fieldError != null && fieldError.getDefaultMessage() != null)
				? fieldError.getDefaultMessage()
				: ErrorCode.INVALID_INPUT.getMessage();
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
	}

	/** 미분류 예외 — 원인은 로그에만 남기고 클라이언트에는 고정 메시지를 준다. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage()));
	}
}
