package com.ssafy.thispatch.domain.game.exception;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MyGameUnregisterErrorCode implements ErrorCode {

	GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "게임을 찾을 수 없습니다."),
	MY_GAME_NOT_REGISTERED(HttpStatus.NOT_FOUND, "내 게임에 등록되지 않은 게임입니다.");

	private final HttpStatus status;
	private final String message;

	@Override
	public String getCode() {
		return name();
	}
}
