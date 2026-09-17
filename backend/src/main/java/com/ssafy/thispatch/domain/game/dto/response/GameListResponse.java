package com.ssafy.thispatch.domain.game.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.ssafy.thispatch.common.TimeRule;

public record GameListResponse(String code, String message, String responsedAt, GameListData data, boolean success) {

	private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static GameListResponse success(GameListData data) {
		return new GameListResponse("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(RESPONSE_TIME_FORMAT), data, true);
	}

	public record GameListData(List<GameItem> items, Page page) {
	}

	public record GameItem(long id, String capsuleImageUrl, String title, List<TagItem> tags,
		Integer positiveRate, boolean isMine, GameSummary gameSummary) {
	}

	public record TagItem(int id, String name) {
	}

	public record GameSummary(long id, String title, String headerImageUrl, LocalDate releasedOn,
		String developer, List<String> playModes, String description, List<String> userTags,
		Integer reviewCount, String latestPatch) {
	}

	public record Page(int limit, String nextCursor, boolean hasNext, long totalCount) {
	}
}