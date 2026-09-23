package com.ssafy.thispatch.domain.game.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.thispatch.domain.game.dto.request.GameListFilters;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest({GameListController.class, MyGameListController.class})
@Import({GameListService.class, GameListCursorCodec.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class GameListFilterControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	@Autowired private MockMvc mvc;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private GameListRepository repository;

	@ParameterizedTest
	@ValueSource(strings = {"/games", "/members/me/games"})
	void validatesOriginalSearchLengthBeforeRemovingPunctuation(String path) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth()).param("search", "!".repeat(101)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors[0].field").value("search"))
			.andExpect(jsonPath("$.errors[0].message").value("검색어는 100자 이하여야 합니다."))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/games", "/members/me/games"})
	void passesAllFiltersAndTheirInclusiveLimitsToTheCorrectScope(String path) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth())
			.param("releaseYearFrom", "1").param("releaseYearTo", "9999")
			.param("minReviewCount", "0").param("maxReviewCount", "2147483647")
			.param("minPositiveRate", "0").param("maxPositiveRate", "100")
			.param("developer", "  VaLvE  "))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.page.totalCount").value(0));
		var filters = new GameListFilters(1, 9999, 0, Integer.MAX_VALUE, 0, 100, "valve");
		verify(repository).count(MEMBER_ID,
			GameListQuery.of(null, GameListSort.POSITIVE_RATE_ASC, 10, null, filters), scope(path));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/games", "/members/me/games"})
	void blankDeveloperIsEquivalentToNoFilter(String path) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth()).param("developer", "  "))
			.andExpect(status().isOk());
		verify(repository).count(MEMBER_ID,
			GameListQuery.of(null, GameListSort.POSITIVE_RATE_ASC, 10, null), scope(path));
	}

	@ParameterizedTest
	@MethodSource("invalidFormats")
	void rejectsMalformedNumbersBeforeLookup(String path, String field, String value) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth()).param(field, value))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value("올바르지 않은 요청입니다."))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@MethodSource("invalidRanges")
	void returnsSafeFieldErrorsForInvalidRanges(String path, String field, String value) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth()).param(field, value))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors[0].field").value(field))
			.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@MethodSource("reversedRanges")
	void reportsUpperFieldWhenBoundsAreReversed(String path, String lower, String upper) throws Exception {
		mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth()).param(lower, "90").param(upper, "70"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value(upper))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
	}

	private static Stream<Arguments> invalidFormats() {
		return paths().flatMap(path -> Stream.of("releaseYearFrom", "releaseYearTo", "minReviewCount",
			"maxReviewCount", "minPositiveRate", "maxPositiveRate").flatMap(field ->
				Stream.of("", " ", "private-value", "1.5", "2147483648", "-2147483649")
					.map(value -> Arguments.of(path, field, value))));
	}

	private static Stream<Arguments> invalidRanges() {
		return paths().flatMap(path -> List.of(
			new String[]{"releaseYearFrom", "0"}, new String[]{"releaseYearTo", "10000"},
			new String[]{"minReviewCount", "-1"}, new String[]{"maxReviewCount", "-1"},
			new String[]{"minPositiveRate", "-1"}, new String[]{"maxPositiveRate", "101"},
			new String[]{"developer", " ".repeat(501)}
		).stream().map(pair -> Arguments.of(path, pair[0], pair[1])));
	}

	private static Stream<Arguments> reversedRanges() {
		return paths().flatMap(path -> List.of(
			new String[]{"releaseYearFrom", "releaseYearTo"},
			new String[]{"minReviewCount", "maxReviewCount"},
			new String[]{"minPositiveRate", "maxPositiveRate"}
		).stream().map(pair -> Arguments.of(path, pair[0], pair[1])));
	}

	private static Stream<String> paths() {
		return Stream.of("/games", "/members/me/games");
	}

	private GameListScope scope(String path) {
		return path.equals("/games") ? GameListScope.ALL : GameListScope.MY;
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(MEMBER_ID);
	}
}
