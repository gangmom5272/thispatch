package com.ssafy.thispatch.domain.statistics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.ssafy.thispatch.common.TimeRule;

public record ReviewPeriod(LocalDate startDate, LocalDate endDate) {

	public ReviewPeriod {
		Objects.requireNonNull(startDate);
		Objects.requireNonNull(endDate);
		if (startDate.isAfter(endDate)) {
			throw new IllegalArgumentException("Start date must not follow end date");
		}
	}

	public static ReviewPeriod recentFourteenDays(LocalDate today) {
		return new ReviewPeriod(today.minusDays(13), today);
	}

	public long dayCount() {
		return ChronoUnit.DAYS.between(startDate, endDate) + 1;
	}

	public Instant startInclusive() {
		return startDate.atStartOfDay(TimeRule.ZONE).toInstant();
	}

	public Instant endExclusive() {
		return endDate.plusDays(1).atStartOfDay(TimeRule.ZONE).toInstant();
	}
}
