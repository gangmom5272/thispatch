package com.ssafy.thispatch.domain.statistics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.BandReview;
import com.ssafy.thispatch.domain.statistics.repository.*;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository.DailyCounts;
import com.ssafy.thispatch.domain.statistics.repository.LanguageStatisticsRepository.LanguageCount;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.BandCount;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.TopicCount;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository.Patch;
import com.ssafy.thispatch.domain.statistics.service.*;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(StatisticsController.class)
@Import({LanguageAnalysisService.class, ReactionTrendsService.class, PlaytimeAnalysisService.class,
	SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class StatisticsControllerTest extends ActiveMemberWebMvcTest {

	private static final ReviewPeriod PERIOD = ReviewPeriod.recentFourteenDays(LocalDate.of(2026, 9, 16));
	@Autowired MockMvc mvc;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean AnalysisContext context;
	@MockitoBean LanguageStatisticsRepository languages;
	@MockitoBean DailyStatisticsRepository daily;
	@MockitoBean ReactionPatchRepository patches;
	@MockitoBean PlaytimeAnalysisRepository playtime;

	@BeforeEach
	void period() {
		when(context.recentPeriod()).thenReturn(PERIOD);
		when(context.periodStarting(PERIOD.startDate())).thenReturn(PERIOD);
		when(context.periodBetween(any(), any())).thenCallRealMethod();
	}

	@Test
	void languageRatesIncludeSmallSamplesAndUseUnifiedFlag() throws Exception {
		when(languages.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new LanguageCount("english", "영어", 80, 56), new LanguageCount("korean", "한국어", 30, 21),
			new LanguageCount("japanese", "일본어", 10, 5)));
		mvc.perform(get("/games/7/language-analysis").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalReviewCount").value(120))
			.andExpect(jsonPath("$.data.isSufficientSample").value(true))
			.andExpect(jsonPath("$.data.sampleSufficient").doesNotExist())
			.andExpect(jsonPath("$.data.languages[0].reviewShare").value(66.7))
			.andExpect(jsonPath("$.data.languages[2].isSufficientSample").value(false));
	}

	@Test
	void reactionSummaryUsesWeightedTotalsAndKeepsZeroDaysAndGlobalPatchNumbers() throws Exception {
		var start = PERIOD.startDate();
		when(daily.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new DailyCounts(start, 3, 1, 2, 1, 1, 1),
			new DailyCounts(start.plusDays(1), 0, 0, 0, 0, 0, 0),
			new DailyCounts(start.plusDays(2), 1, null, 0, 0, 1, 0)));
		when(daily.firstStatDate(7)).thenReturn(start.minusDays(10));
		when(patches.findWithinPeriod(7, PERIOD)).thenReturn(List.of(
			new Patch("18446744073709551615", "패치", start.plusDays(1), 6, 7)));
		mvc.perform(get("/games/7/reaction-trends").param("startDate", start.toString()).header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.reviewCount").value(4))
			.andExpect(jsonPath("$.data.meta.period.endDate").value(PERIOD.endDate().toString()))
			.andExpect(jsonPath("$.data.summary.positiveRate").value(50.0))
			.andExpect(jsonPath("$.data.daily[1].dataAvailable").value(true))
			.andExpect(jsonPath("$.data.daily[1].positiveRate").isEmpty())
			.andExpect(jsonPath("$.data.daily[1].patches[0].patchIndex").value(6))
			.andExpect(jsonPath("$.data.daily[1].patches[0].totalPatchCount").value(7));
		verify(context).periodStarting(start);
		verify(context, never()).periodBetween(any(), any());
	}

	@Test
	void reactionTrendsUsesExplicitEndDateForDaysTotalsAndPatches() throws Exception {
		var start = LocalDate.of(2026, 9, 15);
		var end = LocalDate.of(2026, 9, 23);
		var selectedPeriod = new ReviewPeriod(start, end);
		when(context.periodStarting(start)).thenReturn(new ReviewPeriod(start, LocalDate.of(2026, 9, 28)));
		when(daily.findWithinPeriod(7, selectedPeriod)).thenReturn(start.datesUntil(end.plusDays(1))
			.map(date -> new DailyCounts(date, 1, 0, 1, 1, 0, 0)).toList());
		when(daily.firstStatDate(7)).thenReturn(start.minusDays(10));
		when(patches.findWithinPeriod(7, selectedPeriod)).thenReturn(List.of(new Patch("123", "패치", end, 2, 3)));
		mvc.perform(get("/games/7/reaction-trends").param("startDate", start.toString())
			.param("endDate", end.toString()).header("Authorization", auth()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.meta.period.endDate").value("2026-09-23"))
			.andExpect(jsonPath("$.data.meta.period.dayCount").value(9))
			.andExpect(jsonPath("$.data.availablePeriod.endDate").value("2026-09-23"))
			.andExpect(jsonPath("$.data.daily.length()").value(9))
			.andExpect(jsonPath("$.data.daily[8].date").value("2026-09-23"))
			.andExpect(jsonPath("$.data.daily[8].patches[0].id").value("123"))
			.andExpect(jsonPath("$.data.summary.reviewCount").value(9));
		verify(context, never()).periodStarting(any());
		verify(daily).findWithinPeriod(7, selectedPeriod);
		verify(patches).findWithinPeriod(7, selectedPeriod);
	}

	@Test
	void singleDayBeforeFirstStatisticHasNoAvailablePeriod() throws Exception {
		var date = PERIOD.startDate();
		var selectedPeriod = new ReviewPeriod(date, date);
		when(daily.firstStatDate(7)).thenReturn(date.plusDays(1));
		when(daily.findWithinPeriod(7, selectedPeriod)).thenReturn(List.of(new DailyCounts(date, 0, 0, 0, 0, 0, 0)));
		mvc.perform(get("/games/7/reaction-trends").param("startDate", date.toString())
			.param("endDate", date.toString()).header("Authorization", auth()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.meta.period.dayCount").value(1))
			.andExpect(jsonPath("$.data.availablePeriod").isEmpty())
			.andExpect(jsonPath("$.data.daily.length()").value(1))
			.andExpect(jsonPath("$.data.summary.reviewCount").value(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"invalid", "2026-02-30", "2026-09-02"})
	void reactionTrendsRejectsMalformedOrReversedEndDate(String endDate) throws Exception {
		mvc.perform(get("/games/7/reaction-trends").param("startDate", PERIOD.startDate().toString())
			.param("endDate", endDate).header("Authorization", auth()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(daily, patches);
	}

	@Test
	void reactionTrendsRejectsFutureEndDate() throws Exception {
		mvc.perform(get("/games/7/reaction-trends").param("startDate", PERIOD.startDate().toString())
			.param("endDate", LocalDate.now(TimeRule.ZONE).plusDays(1).toString()).header("Authorization", auth()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(daily, patches);
	}

	@Test
	void playtimeUsesStoredBoundariesAndSelectedVersusOverallTopicRates() throws Exception {
		when(playtime.findBandCounts(7, PERIOD)).thenReturn(List.of(
			new BandCount(1, 0, 90, 1000, 40, 20), new BandCount(2, 90, 480, 1000, 40, 20),
			new BandCount(3, 480, 2100, 1000, 0, 0), new BandCount(4, 2100, null, 1000, 0, 0)));
		when(playtime.findTopicCounts(7, PERIOD)).thenReturn(List.of(
			new TopicCount(1, 1, "밸런스", 20), new TopicCount(2, 1, "밸런스", 10),
			new TopicCount(3, 1, "밸런스", 0), new TopicCount(4, 1, "밸런스", 0)));
		mvc.perform(get("/games/7/playtime-topics").param("bandNo", "1").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.scale.sampleCount").value(4000))
			.andExpect(jsonPath("$.data.scale.p25Minutes").value(90))
			.andExpect(jsonPath("$.data.bands.length()").value(4))
			.andExpect(jsonPath("$.data.bands[2].reviewCount").value(0))
			.andExpect(jsonPath("$.data.topics[0].mentionRate").value(50.0))
			.andExpect(jsonPath("$.data.topics[0].overallMentionRate").value(37.5))
			.andExpect(jsonPath("$.data.topics[0].differencePp").value(12.5))
			.andExpect(jsonPath("$.data.topics[0].highestBand.band").value("B1"));
		verify(playtime, never()).findFallbackReviews(anyLong(), any(), any());
	}

	@Test
	void insufficientSampleKeepsFourFallbackGroupsAndSkipsTopicQuery() throws Exception {
		when(playtime.findBandCounts(7, PERIOD)).thenReturn(List.of(
			new BandCount(1, 0, 100, 0, 0, 0), new BandCount(2, 100, 100, 0, 0, 0),
			new BandCount(3, 100, 100, 0, 0, 0), new BandCount(4, 100, null, 29, 29, 10)));
		mvc.perform(get("/games/7/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.isSufficientSample").value(false))
			.andExpect(jsonPath("$.data.fallback.totalCount").value(29))
			.andExpect(jsonPath("$.data.fallback.itemsByBand.length()").value(4))
			.andExpect(jsonPath("$.data.fallback.itemsByBand[3].band").value("B4"));
		verify(playtime, never()).findTopicCounts(anyLong(), any());
	}

	@Test
	void rejectsMalformedDatesMissingDatesAndInvalidBand() throws Exception {
		mvc.perform(get("/games/7/reaction-trends").header("Authorization", auth())).andExpect(status().isBadRequest());
		mvc.perform(get("/games/7/reaction-trends").param("startDate", "invalid").header("Authorization", auth()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/games/7/playtime-topics").param("bandNo", "5").header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(daily, playtime);
	}

	@Test
	void fallbackReviewBodyUsesTheSamePlainTextRules() throws Exception {
		var row = new ReviewRow(91L, "[b]좋아요[/b]<br>재미있어요[img src='map.jpg']", true, 50, 120, "koreana",
			Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-15T15:30:00Z"));
		when(playtime.findFallbackReviews(7, PERIOD, null)).thenReturn(List.of(new BandReview(1, row)));
		mvc.perform(get("/games/7/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.fallback.itemsByBand[0].items[0].id").value(91L))
			.andExpect(jsonPath("$.data.fallback.itemsByBand[0].items[0].body").value("좋아요\n재미있어요"));
	}

	@Test
	void selectedSmallBandFallsBackEvenWhenOverallHasEnoughReviews() throws Exception {
		when(playtime.findBandCounts(7, PERIOD)).thenReturn(List.of(
			new BandCount(1, 0, 90, 1000, 100, 50), new BandCount(2, 90, 480, 1000, 100, 50),
			new BandCount(3, 480, 2100, 1000, 100, 50), new BandCount(4, 2100, null, 1000, 29, 10)));
		mvc.perform(get("/games/7/playtime-topics").param("bandNo", "4").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.overall.isSufficientSample").value(true))
			.andExpect(jsonPath("$.data.isSufficientSample").value(false))
			.andExpect(jsonPath("$.data.fallback.totalCount").value(29));
		verify(playtime).findFallbackReviews(7, PERIOD, 4);
		verify(playtime, never()).findTopicCounts(anyLong(), any());
	}

	@Test
	void noStatisticsReturnsZeroAndNoInventedQuartiles() throws Exception {
		mvc.perform(get("/games/7/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.scale.sampleCount").value(0))
			.andExpect(jsonPath("$.data.scale.p25Minutes").isEmpty())
			.andExpect(jsonPath("$.data.overall.reviewCount").value(0))
			.andExpect(jsonPath("$.data.fallback.itemsByBand.length()").value(4));
	}

	@ParameterizedTest
	@ValueSource(strings = {"language-analysis", "reaction-trends", "playtime-topics"})
	void allEndpointsRequireAuthenticationAndExistingGame(String endpoint) throws Exception {
		String path = "/games/7/" + endpoint;
		mvc.perform(get(path).param("startDate", PERIOD.startDate().toString())).andExpect(status().isUnauthorized());
		doThrow(new BusinessException(GameDetailErrorCode.GAME_NOT_FOUND)).when(context).requireGame(7);
		mvc.perform(get(path).param("startDate", PERIOD.startDate().toString()).header("Authorization", auth()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
			.andExpect(jsonPath("$.success").doesNotExist());
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}
}
