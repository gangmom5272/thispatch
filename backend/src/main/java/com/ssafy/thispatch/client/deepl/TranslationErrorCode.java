package com.ssafy.thispatch.client.deepl;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TranslationErrorCode implements ErrorCode {

	TRANSLATION_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "번역 서비스를 이용할 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	@Override
	public String getCode() {
		return name();
	}
}
