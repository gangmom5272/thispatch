package com.ssafy.thispatch.global.exception;

import org.springframework.http.HttpStatus;

/** 도메인 오류도 이 계약을 구현해 공통 예외 처리에 연결한다. */
public interface ErrorCode {

	HttpStatus getStatus();

	String getCode();

	String getMessage();
}
