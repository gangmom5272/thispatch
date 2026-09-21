package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.ChangeType;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.Direction;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.TargetRole;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.Target;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanDetailRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanDetailRepository.PlanRow;
import com.ssafy.thispatch.domain.patch.service.PatchPlanDetailService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchPlanDetailController.class)
@Import({PatchPlanDetailService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchPlanDetailControllerTest extends ActiveMemberWebMvcTest {

	private static final String PATH = "/members/me/patch-plans/{planId}";
	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long PLAN_ID = 5_000_000_000L;

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties jwtProperties;
	@MockitoBean private PatchPlanDetailRepository repository;

	@Test
	void returnsExactContractUsingAuthenticatedMemberAndLongIds() throws Exception {
		when(repository.findOwnedPlan(MEMBER_ID, PLAN_ID)).thenReturn(Optional.of(new PlanRow(PLAN_ID,
			4_000_000_000L, "현재 게임 이름", "원문 그대로", "저장된 해석",
			OffsetDateTime.parse("2026-09-21T05:32:00.123456Z"))));
		when(repository.findGenreIds(PLAN_ID)).thenReturn(List.of(9, 19));
		when(repository.findConfirmedSlots(PLAN_ID)).thenReturn(List.of(new ConfirmedSlot(
			new Target("Wraith", TargetRole.UNKNOWN), "등장 빈도", ChangeType.MODIFY, Direction.UNKNOWN, null, null)));
		var result = mvc.perform(get(PATH, PLAN_ID).header("Authorization", auth()).queryParam("memberId", "99"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.path("data")).isEqualTo(mapper.readTree("""
			{"planId":5000000000,"gameId":4000000000,"gameTitle":"현재 게임 이름","rawText":"원문 그대로",
			"restatement":{"text":"저장된 해석"},"genreIds":[9,19],
			"confirmedSlots":[{"target":{"name":"Wraith","role":"UNKNOWN"},"attribute":"등장 빈도",
			"changeType":"MODIFY","direction":"UNKNOWN","magnitude":null,"scope":null}],
			"createdAt":"2026-09-21T14:32:00.123456+09:00"}
			"""));
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).findOwnedPlan(MEMBER_ID, PLAN_ID);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsNonPositivePlanIdBeforeQuerying(long planId) throws Exception {
		var result = mvc.perform(get(PATH, planId).header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors.length()").value(1))
			.andExpect(jsonPath("$.errors[0].field").value("planId"))
			.andExpect(jsonPath("$.errors[0].message").value("planId는 1 이상이어야 합니다."));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("errors").get(0).size()).isEqualTo(2);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"private-invalid", "9223372036854775808", "1.5"})
	void rejectsMalformedPlanIdBeforeQuerying(String planId) throws Exception {
		assertError(mvc.perform(get(PATH, planId).header("Authorization", auth())),
			400, "INVALID_REQUEST", "올바르지 않은 요청입니다.");
		verifyNoInteractions(repository);
	}

	@Test
	void unavailableOwnedPlanReturnsNotFoundWithoutReadingChildren() throws Exception {
		when(repository.findOwnedPlan(MEMBER_ID, PLAN_ID)).thenReturn(Optional.empty());
		assertError(mvc.perform(get(PATH, PLAN_ID).header("Authorization", auth())),
			404, "PATCH_PLAN_NOT_FOUND", "기획안 내역을 찾을 수 없습니다.");
		verify(repository).findOwnedPlan(MEMBER_ID, PLAN_ID);
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "invalid", "expired", "refresh", "inactive"})
	void authenticationFailureStopsDetailQueries(String kind) throws Exception {
		var request = get(PATH, PLAN_ID);
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
		assertError(mvc.perform(request), 401, "UNAUTHORIZED", "인증이 필요합니다.")
			.andExpect(header().string("WWW-Authenticate", "Bearer"));
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailureReturnsSafeServerError() throws Exception {
		when(repository.findOwnedPlan(MEMBER_ID, PLAN_ID))
			.thenThrow(new DataAccessResourceFailureException("private-db-detail"));
		assertError(mvc.perform(get(PATH, PLAN_ID).header("Authorization", auth())),
			500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private String auth() { return "Bearer " + tokens.issueAccessToken(MEMBER_ID); }

	private ResultActions assertError(ResultActions result, int expectedStatus, String code, String message) throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message));
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
