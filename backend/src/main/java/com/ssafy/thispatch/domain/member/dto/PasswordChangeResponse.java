package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record PasswordChangeResponse(String code, String message, String responsedAt, boolean success) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static PasswordChangeResponse successResponse() {
		return new PasswordChangeResponse("200", "비밀번호가 변경되었습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), true);
	}
}
