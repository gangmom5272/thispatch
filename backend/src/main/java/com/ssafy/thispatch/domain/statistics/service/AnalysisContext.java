package com.ssafy.thispatch.domain.statistics.service;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AnalysisContext {

	private final JdbcTemplate jdbcTemplate;

	public void requireGame(long gameId) {
		if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"SELECT EXISTS (SELECT 1 FROM game WHERE appid = ?)", Boolean.class, gameId))) {
			throw new BusinessException(GameDetailErrorCode.GAME_NOT_FOUND);
		}
	}

	public ReviewPeriod recentPeriod() {
		return ReviewPeriod.recentFourteenDays(LocalDate.now(TimeRule.ZONE));
	}

	public ReviewPeriod periodStarting(LocalDate startDate) {
		LocalDate today = LocalDate.now(TimeRule.ZONE);
		if (startDate.isAfter(today)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return new ReviewPeriod(startDate, today);
	}

	public ReviewPeriod periodBetween(LocalDate startDate, LocalDate endDate) {
		if (startDate == null || endDate == null || startDate.isAfter(endDate)
			|| endDate.isAfter(LocalDate.now(TimeRule.ZONE))) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return new ReviewPeriod(startDate, endDate);
	}
}
