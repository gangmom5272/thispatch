package com.ssafy.thispatch.domain.game.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.ssafy.thispatch.common.TimeRule;

public record GameDetailResponse(
	String code,
	String message,
	String responsedAt,
	GameDetailData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static GameDetailResponse success(GameDetailData data) {
		return new GameDetailResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), data, true);
	}

	public record GameDetailData(long id, String capsuleImageUrl, String title, List<TagItem> tags,
		Integer positiveRate, boolean isMine, String description, LocalDate releasedOn,
		Integer reviewCount, Instant lastCollectedAt) {
	}

	public record TagItem(int id, String name) {
	}
}
