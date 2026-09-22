package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.GameContext;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.Candidate;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.PatchData;
import com.ssafy.thispatch.domain.patch.service.CaseSearchService;
import com.ssafy.thispatch.domain.patch.service.CaseSearchExecutor;
import com.ssafy.thispatch.domain.patch.service.PlanStructureService;
import com.ssafy.thispatch.domain.patch.service.PlanStructureStorageService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.*;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchPlanningController.class)
@Import({PlanStructureService.class, CaseSearchService.class, CaseSearchExecutor.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchPlanningControllerTest extends ActiveMemberWebMvcTest {
	private static final String SEARCH = """
		{"confirmedSlots":[{"target":{"name":"Axebot","role":"ENEMY"},"attribute":"HP",
		"changeType":"MODIFY","direction":"INCREASE","magnitude":"+20%","scope":null}],"genreIds":[]}
		""";
	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private PatchSearchRepository repository;
	@MockitoBean private AiPatchClient ai;
	@MockitoBean private PlanStructureStorageService storage;

	@BeforeEach
	void gameAndAi() {
		when(repository.findGame(1)).thenReturn(Optional.of(new GameContext(1, "Game", List.of())));
		when(ai.structure(any())).thenReturn(new PlanResponse(List.of(), "qwen", "v1", 1));
		when(storage.save(eq(1L), eq(1L), anyString(), anyList(), anyList(), any())).thenReturn(101L);
		when(ai.embed(any())).thenReturn(new EmbeddingResponse(List.of(List.of(1.0)), 512, "model"));
		when(repository.search(anyList(), anyString(), anyList(), anyList())).thenReturn(List.of());
	}

	@Test
	void parallelComparisonFailureKeepsSafeAiUnavailableResponse() throws Exception {
		var patch = new PatchData("10", 1, "Game", null, "Patch", Instant.parse("2026-09-21T00:00:00Z"),
			10, new BigDecimal("70"), new BigDecimal("75"), new BigDecimal("5"), null, null);
		var change = new CaseChange("modify", "increase", "enemy", "Health increased");
		when(repository.search(anyList(), anyString(), anyList(), anyList()))
			.thenReturn(List.of(new Candidate(patch, .9, List.of(), List.of(change), false)));
		when(ai.cards(any())).thenReturn(new CardsResponse(List.of(new Card("10", "공통", "차이")), List.of(), null));
		when(ai.compare(any())).thenThrow(new BusinessException(AiErrorCode.AI_UNAVAILABLE,
			new IllegalStateException("private-ai-detail")));
		assertError(request("case-searches", SEARCH), 503, "AI_UNAVAILABLE")
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-ai-detail"))));
	}

	@Test
	void structureUsesDataOnlyEnvelopeAndIgnoresClientGenreIds() throws Exception {
		var result = request("plan-structures", "{\"text\":\"적 체력을 상향합니다\",\"genreIds\":[999]}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.gameId").value(1))
			.andExpect(jsonPath("$.data.planId").value(101))
			.andExpect(jsonPath("$.data.genreIds").isEmpty()).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(1);
		verify(storage).save(eq(1L), eq(1L), eq("적 체력을 상향합니다"), anyList(), anyList(), any());
	}

	@Test
	void searchReturns201DefaultSortAndThreeEmptyGroups() throws Exception {
		request("case-searches", SEARCH).andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("201")).andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.sort").value("SIMILARITY_DESC"))
			.andExpect(jsonPath("$.data.totalCount").value(0)).andExpect(jsonPath("$.data.groups.length()").value(3))
			.andExpect(jsonPath("$.data.confirmedSlots[0].changeType").value("MODIFY"))
			.andExpect(jsonPath("$.data.confirmedSlots[0].magnitude").value("+20%"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":\"     \"}", "{\"text\":\"four\"}"})
	void invalidPlanDoesNotCallAi(String body) throws Exception {
		assertError(request("plan-structures", body), 400, "VALIDATION_FAILED");
		verifyNoInteractions(ai);
	}

	@Test
	void tooLongPlanDoesNotCallAi() throws Exception {
		assertError(request("plan-structures", mapper.writeValueAsString(java.util.Map.of("text", "a".repeat(6001)))), 400, "VALIDATION_FAILED");
		verifyNoInteractions(ai);
	}

	@Test
	void storageFailureReturnsSafeServerErrorInsteadOfSuccess() throws Exception {
		when(storage.save(anyLong(), anyLong(), anyString(), anyList(), anyList(), any()))
			.thenThrow(new org.springframework.dao.DataIntegrityViolationException("secret-database-detail"));
		assertError(request("plan-structures", "{\"text\":\"기획안을 입력합니다\"}"), 500, "INTERNAL_SERVER_ERROR")
			.andExpect(jsonPath("$.errors").doesNotExist()).andExpect(jsonPath("$.responsedAt").isString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"confirmedSlots\":[],\"genreIds\":[]}", "{\"confirmedSlots\":[null],\"genreIds\":[]}"})
	void invalidSearchCollectionsDoNotCallAi(String body) throws Exception {
		assertError(request("case-searches", body), 400, "VALIDATION_FAILED");
		verifyNoInteractions(ai);
	}

	@Test
	void unknownCodesMissingTypeAndInvalidSortAreRejected() throws Exception {
		assertError(request("case-searches", SEARCH.replace("INCREASE", "secret-invalid")), 400, "INVALID_REQUEST");
		assertError(request("case-searches", SEARCH.replace("\"changeType\":\"MODIFY\",", "")), 400, "VALIDATION_FAILED");
		assertError(request("case-searches", SEARCH.replace("\"genreIds\":[]", "\"genreIds\":[],\"sort\":\"INVALID\"")), 400, "INVALID_REQUEST");
		verifyNoInteractions(ai);
	}

	@Test
	void missingGameAndAiFailureHaveCommonSafeErrors() throws Exception {
		when(repository.findGame(1)).thenReturn(Optional.empty());
		assertError(request("case-searches", SEARCH), 404, "GAME_NOT_FOUND");
		verifyNoInteractions(ai);
		when(repository.findGame(1)).thenReturn(Optional.of(new GameContext(1, "Game", List.of())));
		when(ai.structure(any())).thenThrow(new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, new RuntimeException("secret-upstream")));
		assertError(request("plan-structures", "{\"text\":\"기획안을 입력합니다\"}"), 500, "INTERNAL_SERVER_ERROR");
	}

	@ParameterizedTest
	@ValueSource(strings = {"plan-structures", "case-searches"})
	void anonymousAndInactiveMembersCannotCallAi(String endpoint) throws Exception {
		assertError(mvc.perform(post("/games/1/" + endpoint).contentType(MediaType.APPLICATION_JSON).content(SEARCH)), 401, "UNAUTHORIZED")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		when(memberAccessService.isActive(1L)).thenReturn(false);
		assertError(request(endpoint, SEARCH), 401, "UNAUTHORIZED");
		verifyNoInteractions(repository, ai);
	}

	private ResultActions request(String endpoint, String json) throws Exception {
		return mvc.perform(post("/games/1/" + endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(1L))
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions assertError(ResultActions result, int status, String code) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("secret-", "java.lang", "Exception");
		return result;
	}
}
