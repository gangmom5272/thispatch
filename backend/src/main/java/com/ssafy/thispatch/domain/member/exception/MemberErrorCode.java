package com.ssafy.thispatch.domain.member.exception;

import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
	EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	SESSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "세션 정보를 조회할 수 없습니다."),
	NICKNAME_ALREADY_SET(HttpStatus.CONFLICT, "이미 닉네임이 설정된 회원입니다."),
	LOGOUT_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
	REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "토큰 재발급을 위해 다시 로그인해주세요."),
	MEMBER_INACTIVE(HttpStatus.UNAUTHORIZED, "토큰을 재발급할 수 없는 회원입니다.");

	private final HttpStatus status;
	private final String message;

	@Override
	public String getCode() {
		return name();
	}
}
