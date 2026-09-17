package com.ssafy.thispatch.domain.review.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.HelpfulPosition;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

public final class ReviewCursor {

	private ReviewCursor() {
	}

	public static String encode(long gameId, LocalDate endDate, Set<Integer> topics, ReviewRow last) {
		String value = scope(gameId, endDate, topics) + ":" + last.helpfulCount() + ":" + last.id();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static HelpfulPosition decode(String cursor, long gameId, LocalDate endDate, Set<Integer> topics) {
		if (cursor == null) {
			return null;
		}
		try {
			if (cursor.isBlank() || cursor.length() > 4096) {
				throw new IllegalArgumentException();
			}
			String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String prefix = scope(gameId, endDate, topics) + ":";
			if (!value.startsWith(prefix)) {
				throw new IllegalArgumentException();
			}
			String[] position = value.substring(prefix.length()).split(":", -1);
			if (position.length != 2) {
				throw new IllegalArgumentException();
			}
			int helpfulCount = Integer.parseInt(position[0]);
			long reviewId = Long.parseLong(position[1]);
			if (helpfulCount < 0 || reviewId <= 0) {
				throw new IllegalArgumentException();
			}
			return new HelpfulPosition(helpfulCount, reviewId);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
	}

	private static String scope(long gameId, LocalDate endDate, Set<Integer> topics) {
		return "1:" + gameId + ":" + endDate + ":"
			+ topics.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
	}
}
