package com.ssafy.thispatch.domain.review.dto.response;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;

public record ReviewItem(long id, String sentiment, boolean isUpdated, Integer playtimeMinutes,
	String languageCode, int helpfulCount, List<Tag> tags, String body, LocalDate reviewDate) {

	public static ReviewItem of(ReviewRow row, List<Tag> tags) {
		return new ReviewItem(row.id(), row.positive() ? "POSITIVE" : "NEGATIVE",
			row.updatedAt().isAfter(row.createdAt()), row.playtimeMinutes(),
			"koreana".equals(row.languageCode()) ? "korean" : row.languageCode(), row.helpfulCount(),
			tags, row.body(), row.updatedAt().atZone(TimeRule.ZONE).toLocalDate());
	}

	public record Tag(int id, String name) {
	}
}
