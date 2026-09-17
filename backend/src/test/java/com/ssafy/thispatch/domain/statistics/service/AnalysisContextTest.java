package com.ssafy.thispatch.domain.statistics.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.global.exception.BusinessException;

class AnalysisContextTest {

	@Test
	void rejectsFutureStartWithoutQueryingAndBuildsInclusiveKstPeriod() {
		var jdbc = mock(JdbcTemplate.class);
		var context = new AnalysisContext(jdbc);
		var today = LocalDate.now(TimeRule.ZONE);
		assertThatThrownBy(() -> context.periodStarting(today.plusDays(1))).isInstanceOf(BusinessException.class);
		assertThat(context.periodStarting(today).dayCount()).isEqualTo(1);
		assertThat(context.recentPeriod().dayCount()).isEqualTo(14);
		verifyNoInteractions(jdbc);
	}
}
