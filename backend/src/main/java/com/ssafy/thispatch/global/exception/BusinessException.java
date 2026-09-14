package com.ssafy.thispatch.global.exception;

import java.util.Objects;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(Objects.requireNonNull(errorCode).getMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(Objects.requireNonNull(errorCode).getMessage(), cause);
		this.errorCode = errorCode;
	}
}
