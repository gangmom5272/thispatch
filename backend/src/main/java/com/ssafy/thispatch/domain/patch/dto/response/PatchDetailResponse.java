package com.ssafy.thispatch.domain.patch.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record PatchDetailResponse(String code, String message, String responsedAt,
	PatchDetailData data, boolean success) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static PatchDetailResponse success(PatchDetailData data) {
		return new PatchDetailResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), data, true);
	}

	public record PatchDetailData(String patchId, long gameId, String title, LocalDate patchedOn,
		Instant publishedAt, String body, String bodyFormat, String url) {
	}
}
