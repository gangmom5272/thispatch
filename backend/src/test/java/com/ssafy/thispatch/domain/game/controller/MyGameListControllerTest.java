package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.game.repository.GameListRepository.GameRow;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(MyGameListController.class)
@Import({GameListService.class, GameListCursorCodec.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MyGameListControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	private static final String PATH = "/members/me/games";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private GameListRepository repository;

	@Test
	void defaultsUseMyScopeAndSerializeExactContractWithoutIsMine() throws Exception {
		var query = GameListQuery.of(null, GameListSort.POSITIVE_RATE_ASC, 10, null);
		when(repository.count(MEMBER_ID, query, GameListScope.MY)).thenReturn(1L);
		when(repository.findPage(MEMBER_ID, query, GameListScope.MY, null)).thenReturn(List.of(
			new GameRow(GAME_ID, "내 게임", null, null, null, null, null, null, true, null, null)));
		when(repository.findTags(List.of(GAME_ID))).thenReturn(Map.of());
		when(repository.findPlayModes(List.of(GAME_ID))).thenReturn(Map.of());
		var response = mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth())
			.queryParam("memberId", "99")).andExpect(status().isOk()).andReturn();
		var body = mapper.readTree(response.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("data")).isEqualTo(mapper.readTree("""
			{"items":[{"id":4000000000,"capsuleImageUrl":null,"title":"내 게임","tags":[],"positiveRate":null,
			 "gameSummary":{"id":4000000000,"title":"내 게임","headerImageUrl":null,"releasedOn":null,
			 "developer":null,"playModes":[],"description":null,"userTags":[],"reviewCount":null,"latestPatch":null}}],
			 "page":{"limit":10,"nextCursor":null,"hasNext":false,"totalCount":1}}
			"""));
		assertThat(body.toString()).doesNotContain("isMine");
		assertThat(response.getRequest().getSession(false)).isNull();
		verify(repository).findPage(MEMBER_ID, query, GameListScope.MY, null);
	}

	@ParameterizedTest
	@CsvSource({"limit,0", "limit,101"})
	void limitValidationContainsOnlySafeFieldDetails(String key, String value) throws Exception {
		assertValidation(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth()).queryParam(key, value)), key);
		verifyNoInteractions(repository);
	}

	@Test
	void searchLengthIsValidatedBeforeNormalization() throws Exception {
		assertValidation(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth())
			.queryParam("search", " ".repeat(101))), "search");
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@CsvSource({"sort,private-invalid-sort", "limit,private-invalid-limit", "genreIds,private-invalid-genre", "cursor,private-invalid-cursor"})
	void malformedQueryUsesCommon400WithoutRepositoryAccess(String key, String value) throws Exception {
		assertError(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth()).queryParam(key, value)),
			400, "INVALID_REQUEST");
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailureReturnsSafeCommon500() throws Exception {
		when(repository.count(eq(MEMBER_ID), any(), eq(GameListScope.MY)))
			.thenThrow(new DataAccessResourceFailureException("private-db-error"));
		assertError(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth())), 500, "INTERNAL_SERVER_ERROR");
	}

	@Test
	void missingAndInactiveMembersAreRejectedBeforeListQueries() throws Exception {
		assertError(mvc.perform(get(PATH)), 401, "UNAUTHORIZED")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertError(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth())), 401, "UNAUTHORIZED");
		verifyNoInteractions(repository);
	}

	@Test
	void authenticationDatabaseFailureDoesNotQueryList() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenThrow(new DataAccessResourceFailureException("private-auth-db"));
		assertError(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth())), 500, "INTERNAL_SERVER_ERROR");
		verifyNoInteractions(repository);
	}

	private String auth() { return "Bearer " + tokens.issueAccessToken(MEMBER_ID); }

	private void assertValidation(ResultActions result, String field) throws Exception {
		result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors.length()").value(1)).andExpect(jsonPath("$.errors[0].field").value(field));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("errors").get(0).size()).isEqualTo(2);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
	}

	private ResultActions assertError(ResultActions result, int expectedStatus, String code) throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		assertThat(body.has("errors")).isFalse();
		assertThat(body.toString()).doesNotContain("private-", "Exception", "rejectedValue", "java.lang");
		return result;
	}
}
