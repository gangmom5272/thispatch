package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.TagItem;
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.game.repository.GameListRepository.GameRow;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(GameListController.class)
@Import({GameListService.class, GameListCursorCodec.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class GameListControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@MockitoBean private GameListRepository repository;

	@Test
	void mapsExactItemAndSummaryContractAndUsesAuthenticatedMember() throws Exception {
		when(repository.count(anyLong(), any(), any())).thenReturn(1L);
		when(repository.findPage(anyLong(), any(), any(), any())).thenReturn(List.of(new GameRow(
			GAME_ID, "게임", "hash/capsule.jpg", "개발사", "설명", LocalDate.of(2026, 9, 9),
			100, 70, true, "패치 1", "70")));
		when(repository.findTags(List.of(GAME_ID))).thenReturn(Map.of(GAME_ID, List.of(new TagItem(2, "액션"))));
		when(repository.findPlayModes(List.of(GAME_ID))).thenReturn(Map.of(GAME_ID, List.of("멀티플레이어")));
		var result = mvc.perform(get("/games").param("memberId", "9").header(HttpHeaders.AUTHORIZATION, auth()))
			.andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("code").asText()).isEqualTo("200");
		assertThat(body.path("message").asText()).isEqualTo("성공했습니다.");
		assertThat(body.path("success").asBoolean()).isTrue();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.get("data")).isEqualTo(mapper.readTree("""
			{"items":[{"id":4000000000,"capsuleImageUrl":"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/4000000000/hash/capsule.jpg",
			 "title":"게임","tags":[{"id":2,"name":"액션"}],"positiveRate":70,"isMine":true,
			 "gameSummary":{"id":4000000000,"title":"게임","headerImageUrl":"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/4000000000/hash/capsule.jpg",
			 "releasedOn":"2026-09-09","developer":"개발사","playModes":["멀티플레이어"],
			 "description":"설명","userTags":["액션"],"reviewCount":100,"latestPatch":"패치 1"}}],
			 "page":{"limit":10,"nextCursor":null,"hasNext":false,"totalCount":1}}
			"""));
		verify(repository).count(MEMBER_ID, GameListQuery.of(null, GameListSort.POSITIVE_RATE_ASC, 10, null),
			GameListScope.ALL);
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	void defaultsToTenAndKeepsEmptyPageFields() throws Exception {
		mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.page.limit").value(10))
			.andExpect(jsonPath("$.data.page.hasNext").value(false)).andExpect(jsonPath("$.data.page.totalCount").value(0))
			.andExpect(jsonPath("$.data.page.nextCursor").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void preservesNullsAndEmptyArraysIncludingBlankStoredValues() throws Exception {
		when(repository.findPage(anyLong(), any(), any(), any())).thenReturn(List.of(new GameRow(
			GAME_ID, "게임", " ", " ", null, null, null, null, false, " ", null)));
		var result = mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, auth())).andExpect(status().isOk()).andReturn();
		var item = mapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/items/0");
		for (String field : List.of("capsuleImageUrl", "positiveRate")) {
			assertThat(item.get(field).isNull()).isTrue();
		}
		var summary = item.get("gameSummary");
		for (String field : List.of("headerImageUrl", "developer", "latestPatch", "releasedOn", "reviewCount", "description")) {
			assertThat(summary.get(field).isNull()).as(field).isTrue();
		}
		assertThat(summary.get("playModes").isEmpty()).isTrue();
		assertThat(summary.get("userTags").isEmpty()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({"limit,0", "limit,101", "limit,-1"})
	void limitRangeErrorsContainFieldDetails(String field, String value) throws Exception {
		assertError(request(field, value), 400, "VALIDATION_FAILED", true)
			.andExpect(jsonPath("$.errors[0].field").value(field));
		verifyNoInteractions(repository);
	}

	@Test
	void overlongSearchFailsBeforeLookup() throws Exception {
		assertError(request("search", "가".repeat(101)), 400, "VALIDATION_FAILED", true)
			.andExpect(jsonPath("$.errors[0].field").value("search"));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@CsvSource({"sort,wrong", "limit,one", "limit,1.5", "limit,2147483648", "genreIds,0",
		"genreIds,-1", "genreIds,2147483648", "genreIds,'1,,2'", "genreIds,'1,'", "genreIds,'1,a'",
		"genreIds,'1, 2'", "genreIds,''", "cursor,''", "cursor,private-invalid-cursor"})
	void malformedConditionsAreSafe400s(String field, String value) throws Exception {
		assertError(request(field, value), 400, "INVALID_REQUEST", false);
		verifyNoInteractions(repository);
	}

	@Test
	void authFailuresAreRejectedBeforeAnyGameLookup() throws Exception {
		assertError(mvc.perform(get("/games")), 401, "UNAUTHORIZED", false)
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		var expired = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
		for (String token : List.of("private-token", expired.issueAccessToken(MEMBER_ID), tokens.issueRefreshToken(MEMBER_ID))) {
			assertError(mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
				401, "UNAUTHORIZED", false);
		}
		assertError(mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, auth(), auth())),
			401, "UNAUTHORIZED", false);
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertError(request("limit", "10"), 401, "UNAUTHORIZED", false);
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailuresRemainSafe500s() throws Exception {
		when(repository.count(anyLong(), any(), any())).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
		assertError(request("limit", "10"), 500, "INTERNAL_SERVER_ERROR", false);
	}

	private ResultActions request(String field, String value) throws Exception {
		return mvc.perform(get("/games").param(field, value).header(HttpHeaders.AUTHORIZATION, auth()));
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(MEMBER_ID);
	}

	private ResultActions assertError(ResultActions result, int expectedStatus, String code, boolean fieldErrors) throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(fieldErrors ? 4 : 3);
		assertThat(body.has("errors")).isEqualTo(fieldErrors);
		assertThat(body.toString()).doesNotContain("private-", "rejectedValue", "Exception", "java.lang", "\"data\"", "\"success\"");
		return result;
	}
}