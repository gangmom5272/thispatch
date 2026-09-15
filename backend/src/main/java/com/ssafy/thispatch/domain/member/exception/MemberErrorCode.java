package com.ssafy.thispatch.domain.member.exception;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
	SESSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "세션 정보를 조회할 수 없습니다."),
	NICKNAME_ALREADY_SET(HttpStatus.CONFLICT, "이미 닉네임이 설정된 회원입니다.");

	private final HttpStatus status;
	private final String message;

	@Override
	public String getCode() {
		return name();
	}
}
