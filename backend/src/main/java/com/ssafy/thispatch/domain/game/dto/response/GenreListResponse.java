package com.ssafy.thispatch.domain.game.dto.response;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.entity.Tag;

public record GenreListResponse(
	String code,
	String message,
	String responsedAt,
	GenreListData data,
	boolean success
) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static GenreListResponse success(List<Tag> tags) {
		List<GenreItem> items = tags.stream()
			.map(tag -> new GenreItem(tag.getTagId(), tag.getNameKo())).toList();
		return new GenreListResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), new GenreListData(items), true);
	}

	public record GenreListData(List<GenreItem> items) {
	}

	public record GenreItem(int id, String name) {
	}
}
