package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thispatch.common.TimeRule;

public record SteamTokenResponse(
	String code,
	String message,
	String responsedAt,
	TokenData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static SteamTokenResponse success(String accessToken, String refreshToken, String nickname) {
		return new SteamTokenResponse("200", "로그인에 성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT),
			new TokenData(accessToken, refreshToken, nickname), true);
	}

	public record TokenData(String accessToken, String refreshToken,
		@JsonInclude(JsonInclude.Include.ALWAYS) String nickname) {
	}
}
