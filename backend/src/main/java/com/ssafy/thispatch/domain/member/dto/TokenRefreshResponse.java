package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record TokenRefreshResponse(
	String code,
	String message,
	String responsedAt,
	TokenData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static TokenRefreshResponse success(String accessToken) {
		return new TokenRefreshResponse("200", "토큰 재발급에 성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT),
			new TokenData(accessToken), true);
	}

	public record TokenData(String accessToken) {
	}
}
