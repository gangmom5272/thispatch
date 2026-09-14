package com.ssafy.thispatch.global.exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.dispatch.common.TimeRule;

public record ErrorResponse(
	String code,
	String message,
	String responsedAt,
	@JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDetail> errors
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public ErrorResponse {
		errors = errors == null ? List.of() : List.copyOf(errors);
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return of(errorCode.getCode(), errorCode.getMessage());
	}

	public static ErrorResponse of(String code, String message) {
		return new ErrorResponse(code, message, responseTime(), List.of());
	}

	public static ErrorResponse validation(List<FieldErrorDetail> errors) {
		return new ErrorResponse(CommonErrorCode.VALIDATION_FAILED.getCode(),
			CommonErrorCode.VALIDATION_FAILED.getMessage(), responseTime(), errors);
	}

	private static String responseTime() {
		return LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT);
	}

	public record FieldErrorDetail(String field, String message) {
	}
}
