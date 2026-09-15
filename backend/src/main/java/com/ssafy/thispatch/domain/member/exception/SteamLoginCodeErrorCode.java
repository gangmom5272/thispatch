package com.ssafy.thispatch.domain.member.exception;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

public enum SteamLoginCodeErrorCode implements ErrorCode {

	STEAM_LOGIN_CODE_INVALID;

	@Override
	public HttpStatus getStatus() {
		return HttpStatus.UNAUTHORIZED;
	}

	@Override
	public String getCode() {
		return name();
	}

	@Override
	public String getMessage() {
		return "Steam 로그인을 다시 진행해주세요.";
	}
}
