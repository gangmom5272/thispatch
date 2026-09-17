package com.ssafy.thispatch.domain.review.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode;
import com.ssafy.thispatch.domain.review.dto.response.ReviewItem.Tag;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.HelpfulPosition;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.review.service.ReviewReadService;
import com.ssafy.thispatch.domain.statistics.service.AnalysisContext;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(ReviewReadController.class)
@Import({ReviewReadService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ReviewReadControllerTest extends ActiveMemberWebMvcTest {

	private static final ReviewPeriod PERIOD = ReviewPeriod.recentFourteenDays(LocalDate.of(2026, 9, 16));
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean ReviewReadRepository repository;
	@MockitoBean AnalysisContext context;

	@BeforeEach
	void preparePeriod() {
		when(context.recentPeriod()).thenReturn(PERIOD);
		when(repository.topicsExist(anySet())).thenReturn(true);
	}

	@Test
	void pageKeepsHelpfulCursorTagsLongIdKoreanLanguageAndKstDate() throws Exception {
		var first = review(4_000_000_001L, 50);
		var second = review(4_000_000_000L, 50);
		when(repository.findHelpfulReviews(7, PERIOD, Set.of(1, 2), null, 2)).thenReturn(List.of(first, second));
		when(repository.countWithinPeriod(7, PERIOD, Set.of(1, 2))).thenReturn(2L);
		when(repository.findTags(List.of(first.id()))).thenReturn(Map.of(first.id(), List.of(new Tag(2, "버그"))));
		var response = mvc.perform(get("/games/7/reviews").param("topicIds", "2", "1", "2")
			.param("limit", "1").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first.id()))
			.andExpect(jsonPath("$.data.items[0].languageCode").value("korean"))
			.andExpect(jsonPath("$.data.items[0].reviewDate").value("2026-09-16"))
			.andExpect(jsonPath("$.data.items[0].isUpdated").value(true))
			.andExpect(jsonPath("$.data.items[0].tags[0].name").value("버그"))
			.andExpect(jsonPath("$.data.page.hasNext").value(true))
			.andExpect(jsonPath("$.data.page.totalCount").value(2))
			.andExpect(jsonPath("$.data.meta.period.dayCount").value(14)).andReturn();
		String cursor = mapper.readTree(response.getResponse().getContentAsByteArray()).at("/data/page/nextCursor").asText();
		var position = new HelpfulPosition(50, first.id());
		when(repository.findHelpfulReviews(7, PERIOD, Set.of(1, 2), position, 2)).thenReturn(List.of(second));
		mvc.perform(get("/games/7/reviews").param("topicIds", "1", "2").param("limit", "1")
			.param("cursor", cursor).header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second.id()))
			.andExpect(jsonPath("$.data.page.hasNext").value(false));
		mvc.perform(get("/games/8/reviews").param("cursor", cursor).header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void representativeResponseContainsFourItemsAndNoPagination() throws Exception {
		when(repository.findRepresentatives(7, PERIOD)).thenReturn(List.of(review(4, 40), review(3, 30), review(2, 20), review(1, 10)));
		mvc.perform(get("/games/7/reviews/representative").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(4))
			.andExpect(jsonPath("$.data.items[3].helpfulCount").value(10))
			.andExpect(jsonPath("$.data.page").doesNotExist()).andExpect(jsonPath("$.success").value(true));
		verify(repository).findTags(List.of(4L, 3L, 2L, 1L));
	}

	@Test
	void emptyReviewsKeepArrayAndZeroCount() throws Exception {
		mvc.perform(get("/games/7/reviews").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.page.limit").value(10))
			.andExpect(jsonPath("$.data.page.totalCount").value(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "101", "-1", "bad"})
	void rejectsInvalidLimit(String limit) throws Exception {
		mvc.perform(get("/games/7/reviews").param("limit", limit).header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsInvalidTopicAndCursor() throws Exception {
		mvc.perform(get("/games/7/reviews").param("topicIds", "-1").header("Authorization", auth()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/games/7/reviews").param("cursor", "private-invalid-cursor").header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("올바르지 않은 요청입니다."));
		when(repository.topicsExist(Set.of(999))).thenReturn(false);
		mvc.perform(get("/games/7/reviews").param("topicIds", "999").header("Authorization", auth()))
			.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/games/7/reviews", "/games/7/reviews/representative"})
	void authenticationAndMissingGameUseCommonErrors(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
		doThrow(new BusinessException(GameDetailErrorCode.GAME_NOT_FOUND)).when(context).requireGame(7);
		mvc.perform(get(path).header("Authorization", auth())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GAME_NOT_FOUND")).andExpect(jsonPath("$.data").doesNotExist());
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}

	private static ReviewRow review(long id, int helpful) {
		return new ReviewRow(id, "리뷰 원문", true, helpful, 120, "koreana",
			Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-15T15:30:00Z"));
	}
}
