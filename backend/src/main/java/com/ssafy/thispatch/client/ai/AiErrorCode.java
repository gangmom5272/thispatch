package com.ssafy.thispatch.client.ai;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

public enum AiErrorCode implements ErrorCode {

	AI_UNAVAILABLE;

	@Override
	public HttpStatus getStatus() {
		return HttpStatus.SERVICE_UNAVAILABLE;
	}

	@Override
	public String getCode() {
		return name();
	}

	@Override
	public String getMessage() {
		return "AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
	}
}
