package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanDeleteRepository;
import com.ssafy.thispatch.domain.patch.service.PatchPlanDeleteService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchPlanDeleteController.class)
@Import({PatchPlanDeleteService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchPlanDeleteControllerTest extends ActiveMemberWebMvcTest {

	private static final String PATH = "/members/me/patch-plans/{planId}";
	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long PLAN_ID = 5_000_000_000L;

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties jwtProperties;
	@MockitoBean private PatchPlanDeleteRepository repository;

	@Test
	void returnsExactSuccessContractUsingAuthenticatedMemberAndLongIds() throws Exception {
		when(repository.lockOwnedCompletedPlan(MEMBER_ID, PLAN_ID)).thenReturn(true);
		var before = LocalDateTime.now(TimeRule.ZONE).withNano(0);
		var result = mvc.perform(delete(PATH, PLAN_ID).header("Authorization", auth()).queryParam("memberId", "99"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").doesNotExist()).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(LocalDateTime.parse(body.path("responsedAt").asText(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
			.isBetween(before, LocalDateTime.now(TimeRule.ZONE));
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).lockOwnedCompletedPlan(MEMBER_ID, PLAN_ID);
		verify(repository).deletePlanAndChildren(PLAN_ID);
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsNonPositivePlanIdBeforeDeletion(long planId) throws Exception {
		var result = mvc.perform(delete(PATH, planId).header("Authorization", auth()))
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
	void rejectsMalformedPlanIdBeforeDeletion(String planId) throws Exception {
		assertError(mvc.perform(delete(PATH, planId).header("Authorization", auth())),
			400, "INVALID_REQUEST", "올바르지 않은 요청입니다.");
		verifyNoInteractions(repository);
	}

	@Test
	void unavailableOwnedCompletedPlanReturnsNotFoundWithoutDeletion() throws Exception {
		assertError(mvc.perform(delete(PATH, PLAN_ID).header("Authorization", auth())),
			404, "PATCH_PLAN_NOT_FOUND", "기획안 내역을 찾을 수 없습니다.");
		verify(repository).lockOwnedCompletedPlan(MEMBER_ID, PLAN_ID);
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "invalid", "expired", "refresh", "inactive"})
	void authenticationFailureStopsDeletion(String kind) throws Exception {
		var request = delete(PATH, PLAN_ID);
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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void lookupAndDeletionFailuresReturnSafeServerError(boolean deletionFails) throws Exception {
		var failure = new DataAccessResourceFailureException("private-db-detail");
		if (deletionFails) {
			when(repository.lockOwnedCompletedPlan(MEMBER_ID, PLAN_ID)).thenReturn(true);
			doThrow(failure).when(repository).deletePlanAndChildren(PLAN_ID);
		} else {
			when(repository.lockOwnedCompletedPlan(MEMBER_ID, PLAN_ID)).thenThrow(failure);
		}
		assertError(mvc.perform(delete(PATH, PLAN_ID).header("Authorization", auth())),
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
