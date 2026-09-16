package com.ssafy.thispatch.domain.game.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record MyGameUnregisterResponse(String code, String message, String responsedAt, boolean success) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static MyGameUnregisterResponse successResponse() {
		return new MyGameUnregisterResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), true);
	}
}
