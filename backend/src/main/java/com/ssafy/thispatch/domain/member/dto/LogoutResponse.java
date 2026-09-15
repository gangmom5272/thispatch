package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thispatch.common.TimeRule;

public record LogoutResponse(
	String code,
	String message,
	String responsedAt,
	@JsonInclude(JsonInclude.Include.ALWAYS) Object data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static LogoutResponse successResponse() {
		return new LogoutResponse("200", "로그아웃되었습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), null, true);
	}
}
