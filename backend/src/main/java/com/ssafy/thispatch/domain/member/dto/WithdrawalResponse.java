package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record WithdrawalResponse(String code, String message, String responsedAt, boolean success) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static WithdrawalResponse successResponse() {
		return new WithdrawalResponse("200", "회원탈퇴가 완료되었습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), true);
	}
}
