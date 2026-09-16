package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record SignupResponse(
	String code,
	String message,
	String responsedAt,
	TokenData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static SignupResponse success(String accessToken, String refreshToken) {
		return new SignupResponse("200", "회원가입에 성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT),
			new TokenData(accessToken, refreshToken), true);
	}

	public record TokenData(String accessToken, String refreshToken) {
	}
}
