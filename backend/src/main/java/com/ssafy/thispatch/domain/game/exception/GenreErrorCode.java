package com.ssafy.thispatch.domain.game.exception;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

public enum GenreErrorCode implements ErrorCode {

	GENRE_LIST_UNAVAILABLE;

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
		return "장르 목록을 조회할 수 없습니다.";
	}
}
