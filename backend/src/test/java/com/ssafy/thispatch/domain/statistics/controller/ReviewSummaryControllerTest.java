package com.ssafy.thispatch.domain.statistics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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

import com.ssafy.thispatch.client.ai.AiReviewClient;
import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiReviewClient.Summary;
import com.ssafy.thispatch.client.ai.AiReviewClient.SummaryRequest;
import com.ssafy.thispatch.domain.review.dto.response.ReviewItem.Tag;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.service.*;
import com.ssafy.thispatch.domain.statistics.service.SummaryReviewReader.Input;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.*;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(ReviewSummaryController.class)
@Import({ReviewSummaryService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ReviewSummaryControllerTest extends ActiveMemberWebMvcTest {

	private static final ReviewPeriod PERIOD = ReviewPeriod.recentFourteenDays(LocalDate.of(2026, 9, 16));
	@Autowired MockMvc mvc;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean AnalysisContext context;
	@MockitoBean SummaryReviewReader reader;
	@MockitoBean AiReviewClient ai;

	@BeforeEach
	void setup() {
		when(context.recentPeriod()).thenReturn(PERIOD);
		when(context.periodStarting(PERIOD.startDate())).thenReturn(PERIOD);
		when(ai.summarize(any())).thenAnswer(call -> {
			SummaryRequest request = call.getArgument(0);
			return new Summary(request.appid(), request.scopeType(), request.scopeKey(), "제목", "요약",
				List.of("전투"), List.of(1L, 2L), request.reviews().size(), "qwen", 1, true, 100);
		});
	}

	@Test
	void playtimeMapsBandAndFortySelectedReviews() throws Exception {
		when(reader.read(7, PERIOD, 2, null, 40)).thenReturn(input(80, 40));
		mvc.perform(get("/games/7/summaries/playtime-topics").param("bandNo", "2")
			.param("startDate", PERIOD.startDate().toString()).header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.selectedBand").value("B2"))
			.andExpect(jsonPath("$.data.summary.status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.summary.targetReviewCount").value(80))
			.andExpect(jsonPath("$.data.summary.usedReviewCount").value(40))
			.andExpect(jsonPath("$.data.summary.recurringExpressions[0]").value("전투"));
		verify(ai).summarize(argThat(request -> request.scopeType().equals("BAND")
			&& request.scopeKey().equals("2") && request.reviews().size() == 40));
	}

	@Test
	void thirtyReviewsAreEnoughAndLongUnicodeTextIsBounded() throws Exception {
		when(reader.read(7, PERIOD, null, null, 40)).thenReturn(input(30, 30));
		mvc.perform(get("/games/7/summaries/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("COMPLETED"));
		verify(ai).summarize(argThat(request -> request.scopeType().equals("ALL") && request.scopeKey().isEmpty()
			&& request.reviews().get(0).reviewText().codePointCount(0, request.reviews().get(0).reviewText().length()) == 600));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 29})
	void insufficientSampleNeverCallsAi(int count) throws Exception {
		when(reader.read(7, PERIOD, null, null, 40)).thenReturn(input(count, count));
		mvc.perform(get("/games/7/summaries/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.summary.reasonCode").value("INSUFFICIENT_SAMPLE"))
			.andExpect(jsonPath("$.data.summary.text").isEmpty())
			.andExpect(jsonPath("$.data.summary.usedReviewCount").isEmpty());
		verifyNoInteractions(ai);
	}

	@Test
	void languageSelectsTwentyAndKeepsFourHelpfulRepresentativesWithTags() throws Exception {
		when(reader.read(7, PERIOD, null, "korean", 20)).thenReturn(input(80, 20));
		mvc.perform(get("/games/7/language-analysis/korean").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.languageCode").value("korean"))
			.andExpect(jsonPath("$.data.summary.usedReviewCount").value(20))
			.andExpect(jsonPath("$.data.summary.recurringExpressions").doesNotExist())
			.andExpect(jsonPath("$.data.representativeReviews.length()").value(4))
			.andExpect(jsonPath("$.data.representativeReviews[0].languageCode").value("korean"))
			.andExpect(jsonPath("$.data.representativeReviews[0].tags[0].id").value(1));
		verify(ai).summarize(argThat(request -> request.scopeType().equals("LANGUAGE")
			&& request.scopeKey().equals("korean") && request.reviews().size() == 20));
	}

	@Test
	void emptyLanguageStillReturnsEmptyReviewsWithoutAi() throws Exception {
		when(reader.read(7, PERIOD, null, "korean", 20)).thenReturn(input(0, 0));
		mvc.perform(get("/games/7/language-analysis/korean").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.representativeReviews").isEmpty());
		verifyNoInteractions(ai);
	}

	@ParameterizedTest
	@ValueSource(strings = {"summaries/playtime-topics?bandNo=5", "summaries/playtime-topics?bandNo=oops", "language-analysis/koreana"})
	void invalidParameters(String path) throws Exception {
		mvc.perform(get("/games/7/" + path).header("Authorization", auth())).andExpect(status().isBadRequest());
		verifyNoInteractions(ai, reader);
	}

	@Test
	void unavailableAiKeepsPlaytimePeriodAndTargetCount() throws Exception {
		when(reader.read(7, PERIOD, null, null, 40)).thenReturn(input(80, 40));
		doThrow(new BusinessException(AiErrorCode.AI_UNAVAILABLE)).when(ai).summarize(any());
		mvc.perform(get("/games/7/summaries/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.reasonCode").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.message").value("AI 요약을 일시적으로 이용할 수 없습니다."))
			.andExpect(jsonPath("$.data.summary.targetReviewCount").value(80))
			.andExpect(jsonPath("$.data.summary.targetPeriod.startDate").value(PERIOD.startDate().toString()))
			.andExpect(jsonPath("$.data.summary.text").isEmpty())
			.andExpect(jsonPath("$.data.summary.usedReviewCount").isEmpty())
			.andExpect(jsonPath("$.data.summary.recurringExpressions").isEmpty());
	}

	@Test
	void unavailableAiKeepsLanguageRepresentativesAndTags() throws Exception {
		when(reader.read(7, PERIOD, null, "korean", 20)).thenReturn(input(80, 20));
		doThrow(new BusinessException(AiErrorCode.AI_UNAVAILABLE)).when(ai).summarize(any());
		mvc.perform(get("/games/7/language-analysis/korean").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.reasonCode").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.data.summary.message").value("AI 요약을 일시적으로 이용할 수 없습니다."))
			.andExpect(jsonPath("$.data.summary.targetReviewCount").value(80))
			.andExpect(jsonPath("$.data.representativeReviews.length()").value(4))
			.andExpect(jsonPath("$.data.representativeReviews[0].tags[0].id").value(1));
	}

	@Test
	void databaseFailureIsNotReportedAsAiUnavailable() throws Exception {
		when(reader.read(7, PERIOD, null, "korean", 20)).thenThrow(new IllegalStateException("database unavailable"));
		mvc.perform(get("/games/7/language-analysis/korean").header("Authorization", auth()))
			.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(ai);
	}

	@Test
	void failureUsesExistingErrorEnvelope() throws Exception {
		when(reader.read(7, PERIOD, null, null, 40)).thenReturn(input(30, 30));
		doThrow(new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR)).when(ai).summarize(any());
		mvc.perform(get("/games/7/summaries/playtime-topics").header("Authorization", auth()))
			.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"summaries/playtime-topics", "language-analysis/korean"})
	void authenticationRequired(String endpoint) throws Exception {
		mvc.perform(get("/games/7/" + endpoint)).andExpect(status().isUnauthorized());
		verifyNoInteractions(reader, ai);
	}

	private Input input(long total, int selectedCount) {
		List<ReviewRow> rows = IntStream.rangeClosed(1, selectedCount).mapToObj(id -> new ReviewRow(id,
			"😀".repeat(900), true, 100 - id, 90, "koreana", Instant.parse("2026-09-01T00:00:00Z"),
			Instant.parse("2026-09-15T15:30:00Z"))).toList();
		return new Input("게임", total, rows, Map.of(1L, List.of(new Tag(1, "밸런스"))));
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}
}
