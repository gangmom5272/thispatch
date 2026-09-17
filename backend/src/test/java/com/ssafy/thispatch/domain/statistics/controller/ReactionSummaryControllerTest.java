package com.ssafy.thispatch.domain.statistics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiTrendClient;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository.DailyCounts;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository.Patch;
import com.ssafy.thispatch.domain.statistics.service.AnalysisContext;
import com.ssafy.thispatch.domain.statistics.service.ReactionSummaryService;
import com.ssafy.thispatch.domain.statistics.service.ReactionSummaryReader;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(ReactionSummaryController.class)
@Import({ReactionSummaryService.class, ReactionSummaryReader.class, AnalysisContext.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ReactionSummaryControllerTest extends ActiveMemberWebMvcTest {

	private static final String PATH = "/games/7/summaries/reaction-trends";
	private static final ReviewPeriod PERIOD = new ReviewPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14));
	@Autowired MockMvc mvc;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean JdbcTemplate jdbc;
	@MockitoBean DailyStatisticsRepository repository;
	@MockitoBean ReactionPatchRepository patches;
	@MockitoBean AiTrendClient ai;

	@BeforeEach
	void gameExists() {
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(7L))).thenReturn(true);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 29})
	void insufficientPeriodUsesSkippedAndRequestedEndDate(int count) throws Exception {
		when(repository.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new DailyCounts(PERIOD.startDate(), count, 0, count, count, 0, 0)));
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.meta.period.endDate").value("2026-01-14"))
			.andExpect(jsonPath("$.data.meta.period.dayCount").value(14))
			.andExpect(jsonPath("$.data.summary.status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.summary.reasonCode").value("INSUFFICIENT_SAMPLE"))
			.andExpect(jsonPath("$.data.summary.text").isEmpty());
		verify(repository).findWithinPeriod(7, PERIOD);
		verifyNoInteractions(ai);
	}

	@Test
	void aiOutageIsNotMisreportedAsInsufficientOrCompleted() throws Exception {
		when(repository.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new DailyCounts(PERIOD.startDate(), 10, 0, 10, 10, 0, 0),
			new DailyCounts(PERIOD.endDate(), 20, 0, 20, 20, 0, 0)));
		when(ai.summarize(any())).thenThrow(new BusinessException(AiErrorCode.AI_UNAVAILABLE));
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.reasonCode").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.message").value("AI 요약을 일시적으로 이용할 수 없습니다."))
			.andExpect(jsonPath("$.data.summary.text").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void modelAndTemplateSummariesPreserveCaveatsAndSendStatistics(boolean usedLlm) throws Exception {
		when(repository.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new DailyCounts(PERIOD.startDate(), 30, 10, 20, 15, 10, 5),
			new DailyCounts(PERIOD.endDate(), 40, null, 30, 20, 10, 3)));
		when(patches.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new Patch("18446744073709551615", "패치", PERIOD.startDate(), 1, 1)));
		when(ai.summarize(any())).thenReturn(new AiTrendClient.Result(7L, "기간 요약", List.of("인과관계는 아닙니다."), usedLlm, usedLlm));
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary.status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.summary.text").value("기간 요약\n\n인과관계는 아닙니다."))
			.andExpect(jsonPath("$.data.summary.targetPeriod.endDate").value("2026-01-14"))
			.andExpect(jsonPath("$.data.summary.reasonCode").isEmpty())
			.andExpect(jsonPath("$.data.summary.message").doesNotExist());
		verify(ai).summarize(new AiTrendClient.Request(7, List.of(
			new AiTrendClient.Day(PERIOD.startDate(), 30, 20, 20, 15, 10, 5),
			new AiTrendClient.Day(PERIOD.endDate(), 40, 23, 30, 20, 10, 3)),
			List.of(new AiTrendClient.Patch(PERIOD.startDate(), "패치", "18446744073709551615")), 7, true));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 400})
	void acceptsAiPeriodBoundaries(int dayCount) throws Exception {
		LocalDate endDate = LocalDate.of(2026, 1, 14);
		mvc.perform(get(PATH).param("startDate", endDate.minusDays(dayCount - 1).toString())
			.param("endDate", endDate.toString()).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.meta.period.dayCount").value(dayCount));
		verifyNoInteractions(ai);
	}

	@Test
	void rejectsMoreThanFourHundredDaysBeforeQuery() throws Exception {
		mvc.perform(get(PATH).param("startDate", "2024-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isBadRequest());
		verifyNoInteractions(repository, patches, ai);
	}

	@Test
	void aiInputValidationFailureRemainsServerError() throws Exception {
		when(repository.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new DailyCounts(PERIOD.startDate(), 30, 0, 30, 30, 0, 0)));
		when(ai.summarize(any())).thenThrow(new IllegalStateException("AI rejected backend trend statistics"));
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"startDate=2026-01-14&endDate=2026-01-01", "startDate=bad&endDate=2026-01-14", "startDate=2026-01-01"})
	void rejectsInvalidAndMissingDatesBeforeQuery(String query) throws Exception {
		mvc.perform(get(PATH + "?" + query).header("Authorization", auth())).andExpect(status().isBadRequest());
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsFutureEndDateBeforeQuery() throws Exception {
		mvc.perform(get(PATH).param("startDate", "2026-01-01")
			.param("endDate", LocalDate.now(TimeRule.ZONE).plusDays(1).toString()).header("Authorization", auth()))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(repository);
	}

	@Test
	void authenticationAndMissingGameUseExistingErrors() throws Exception {
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14"))
			.andExpect(status().isUnauthorized());
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(7L))).thenReturn(false);
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailureRemainsServerError() throws Exception {
		when(repository.findWithinPeriod(7, PERIOD)).thenThrow(new IllegalStateException("database unavailable"));
		mvc.perform(get(PATH).param("startDate", "2026-01-01").param("endDate", "2026-01-14")
			.header("Authorization", auth())).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}
}
