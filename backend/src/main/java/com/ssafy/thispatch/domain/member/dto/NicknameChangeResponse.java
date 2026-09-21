package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record NicknameChangeResponse(
	String code,
	String message,
	String responsedAt,
	NicknameData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static NicknameChangeResponse of(String nickname) {
		return new NicknameChangeResponse("200", "닉네임이 변경되었습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), new NicknameData(nickname), true);
	}

	public record NicknameData(String nickname) {
	}
}
