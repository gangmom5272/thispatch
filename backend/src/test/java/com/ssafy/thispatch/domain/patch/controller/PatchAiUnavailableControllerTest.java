package com.ssafy.thispatch.domain.patch.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.domain.patch.service.CaseSearchService;
import com.ssafy.thispatch.domain.patch.service.PlanStructureService;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.MemberPrincipal;

class PatchAiUnavailableControllerTest {

	@ParameterizedTest
	@ValueSource(strings = {"plan-structures", "case-searches"})
	void aiFailureReturnsRetryMessageInsteadOfSuccessOrEmptyResults(String endpoint) throws Exception {
		var plans = mock(PlanStructureService.class);
		var searches = mock(CaseSearchService.class);
		var unavailable = new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		when(plans.structure(eq(7L), eq(1L), any())).thenThrow(unavailable);
		when(searches.search(eq(1L), any())).thenThrow(unavailable);
		var mvc = MockMvcBuilders.standaloneSetup(new PatchPlanningController(plans, searches))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler()).build();
		String body = endpoint.equals("plan-structures") ? "{\"text\":\"적의 체력을 올립니다\"}" : """
			{"confirmedSlots":[{"target":{"name":"Enemy","role":"ENEMY"},"attribute":"HP",
			"changeType":"MODIFY","direction":"INCREASE"}],"genreIds":[]}
			""";

		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(new MemberPrincipal(7L), null, java.util.List.of()));
		try {
			mvc.perform(post("/games/1/" + endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist());
		} finally {
			SecurityContextHolder.clearContext();
		}
	}
}
