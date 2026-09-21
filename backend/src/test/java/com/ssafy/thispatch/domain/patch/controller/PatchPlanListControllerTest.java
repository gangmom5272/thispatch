package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanListRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanListRepository.PlanRow;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec.Boundary;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchPlanListController.class)
@Import({PatchPlanListService.class, PatchPlanListCursorCodec.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchPlanListControllerTest extends ActiveMemberWebMvcTest {

	private static final String PATH = "/members/me/patch-plans";
	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-21T05:32:00.123456Z");

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties jwtProperties;
	@Autowired private PatchPlanListCursorCodec cursors;
	@MockitoBean private PatchPlanListRepository repository;

	@Test
	void defaultQueryUsesAuthenticatedMemberAndExactSuccessContract() throws Exception {
		when(repository.count(MEMBER_ID, null)).thenReturn(1L);
		when(repository.findPage(MEMBER_ID, null, 10, null)).thenReturn(List.of(
			new PlanRow(5_000_000_000L, GAME_ID, "현재 게임 이름", "원문 그대로", 3, 1, CREATED_AT)));
		var result = mvc.perform(get(PATH).header("Authorization", auth()).queryParam("memberId", "99"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.path("data")).isEqualTo(mapper.readTree("""
			{"items":[{"planId":5000000000,"gameId":4000000000,"gameTitle":"현재 게임 이름",
			"rawTextPreview":"원문 그대로","slotCount":3,"unknownEntityCount":1,"createdAt":"2026-09-21T14:32:00.123456+09:00"}],
			"page":{"limit":10,"nextCursor":null,"hasNext":false,"totalCount":1}}
			"""));
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).findPage(MEMBER_ID, null, 10, null);
	}

	@Test
	void nextCursorCanBeUsedWithDifferentLimitAndKeepsTotalCount() throws Exception {
		var newest = new PlanRow(12, GAME_ID, "게임", "원문", 1, 0, CREATED_AT);
		var oldest = new PlanRow(11, GAME_ID, "게임", "원문", 1, 0, CREATED_AT);
		when(repository.count(MEMBER_ID, GAME_ID)).thenReturn(2L);
		when(repository.findPage(MEMBER_ID, GAME_ID, 1, null)).thenReturn(List.of(newest, oldest));
		var first = mvc.perform(get(PATH).header("Authorization", auth()).queryParam("limit", "1")
			.queryParam("gameId", Long.toString(GAME_ID))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.page.hasNext").value(true))
			.andReturn();
		String cursor = mapper.readTree(first.getResponse().getContentAsByteArray()).at("/data/page/nextCursor").asText();
		var boundary = new Boundary(newest.planId(), CREATED_AT);
		when(repository.findPage(MEMBER_ID, GAME_ID, 100, boundary)).thenReturn(List.of(oldest));
		mvc.perform(get(PATH).header("Authorization", auth()).queryParam("limit", "100")
			.queryParam("gameId", Long.toString(GAME_ID)).queryParam("cursor", cursor)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].planId").value(11)).andExpect(jsonPath("$.data.page.limit").value(100))
			.andExpect(jsonPath("$.data.page.totalCount").value(2)).andExpect(jsonPath("$.data.page.hasNext").value(false))
			.andExpect(jsonPath("$.data.page.nextCursor").isEmpty());
	}

	@ParameterizedTest
	@CsvSource({"gameId,0", "gameId,-1", "limit,0", "limit,-1", "limit,101"})
	void validatesPositiveGameIdAndLimitRange(String name, String value) throws Exception {
		var result = mvc.perform(get(PATH).header("Authorization", auth()).queryParam(name, value))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors.length()").value(1)).andExpect(jsonPath("$.errors[0].field").value(name));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("errors").get(0).size()).isEqualTo(2);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@CsvSource({"gameId,private-invalid", "gameId,9223372036854775808", "gameId,1.5",
		"limit,private-invalid", "limit,2147483648", "cursor,private-invalid", "cursor,''"})
	void rejectsMalformedParametersBeforeQuerying(String name, String value) throws Exception {
		assertError(mvc.perform(get(PATH).header("Authorization", auth()).queryParam(name, value)), 400, "INVALID_REQUEST");
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsMemberAndFilterMismatchesBeforeQuerying() throws Exception {
		for (String cursor : List.of(cursors.encode(MEMBER_ID + 1, null, new Boundary(1, CREATED_AT)),
			cursors.encode(MEMBER_ID, GAME_ID, new Boundary(1, CREATED_AT)))) {
			assertError(mvc.perform(get(PATH).header("Authorization", auth()).queryParam("cursor", cursor)),
				400, "INVALID_REQUEST");
		}
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "invalid", "expired", "refresh", "inactive"})
	void authenticationFailureStopsListQueries(String kind) throws Exception {
		var request = get(PATH);
		switch (kind) {
			case "invalid" -> request.header("Authorization", "Bearer private-invalid-token");
			case "expired" -> request.header("Authorization", "Bearer " + new JwtTokenProvider(jwtProperties,
				Clock.fixed(Instant.now().minusSeconds(86400), ZoneOffset.UTC)).issueAccessToken(MEMBER_ID));
			case "refresh" -> request.header("Authorization", "Bearer " + tokens.issueRefreshToken(MEMBER_ID));
			case "inactive" -> {
				when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
				request.header("Authorization", auth());
			}
		}
		assertError(mvc.perform(request), 401, "UNAUTHORIZED").andExpect(header().string("WWW-Authenticate", "Bearer"));
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailureReturnsSafeServerError() throws Exception {
		when(repository.count(MEMBER_ID, null)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
		assertError(mvc.perform(get(PATH).header("Authorization", auth())), 500, "INTERNAL_SERVER_ERROR");
	}

	private String auth() { return "Bearer " + tokens.issueAccessToken(MEMBER_ID); }

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
