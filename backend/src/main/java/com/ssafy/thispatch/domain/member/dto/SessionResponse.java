package com.ssafy.thispatch.domain.member.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.entity.Member;

public record SessionResponse(
	String code,
	String message,
	String responsedAt,
	SessionData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static SessionResponse anonymous() {
		return success(new SessionData(false, null));
	}

	public static SessionResponse authenticated(Member member) {
		return success(new SessionData(true, new SessionUser(member.getMemberId(), member.getNickname())));
	}

	private static SessionResponse success(SessionData data) {
		return new SessionResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), data, true);
	}

	public record SessionData(boolean authenticated, @JsonInclude(JsonInclude.Include.ALWAYS) SessionUser user) {
	}

	public record SessionUser(long id, @JsonInclude(JsonInclude.Include.ALWAYS) String nickname) {
	}
}
