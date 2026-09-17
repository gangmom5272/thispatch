package com.ssafy.thispatch.domain.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ReviewPeriodTest {

	@Test
	void fourteenDaysIncludeTodayAndRespectKoreanMidnight() {
		var period = ReviewPeriod.recentFourteenDays(LocalDate.of(2026, 9, 15));
		assertThat(period.dayCount()).isEqualTo(14);
		assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 9, 2));
		assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-09-01T15:00:00Z"));
		assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-09-15T15:00:00Z"));
	}

	@Test
	void customPeriodCrossesYearAndLeapDayWithoutDroppingADay() {
		assertThat(new ReviewPeriod(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1)).dayCount()).isEqualTo(3);
		assertThat(new ReviewPeriod(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)).dayCount()).isEqualTo(2);
	}

	@Test
	void reversedPeriodIsRejected() {
		assertThatThrownBy(() -> new ReviewPeriod(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 14)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
