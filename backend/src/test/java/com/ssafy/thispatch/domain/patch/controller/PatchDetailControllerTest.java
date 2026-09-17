package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
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
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository.PatchRow;
import com.ssafy.thispatch.domain.patch.service.PatchDetailService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchDetailController.class)
@Import({PatchDetailService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchDetailControllerTest extends ActiveMemberWebMvcTest {

	private static final long GAME_ID = 4_000_000_000L;
	private static final String PATCH_ID = "18446744073709551615";
	private static final String PATH = "/games/{gameId}/patches/{patchId}";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private PatchReadRepository repository;

	@Test
	void returnsOriginalWithStringIdAndPublicationDateInKorea() throws Exception {
		when(repository.gameExists(GAME_ID)).thenReturn(true);
		when(repository.find(GAME_ID, PATCH_ID)).thenReturn(Optional.of(new PatchRow(
			PATCH_ID, GAME_ID, "Patch", Instant.parse("2026-09-15T15:30:00Z"),
			"[h1]Balance[/h1][list][*]HP +20%[/list]", null)));
		var result = request(Long.toString(GAME_ID), PATCH_ID).andExpect(status().isOk()).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("code").asText()).isEqualTo("200");
		assertThat(body.path("message").asText()).isEqualTo("성공했습니다.");
		assertThat(body.path("success").asBoolean()).isTrue();
		assertThat(body.get("data")).isEqualTo(mapper.readTree("""
			{"patchId":"18446744073709551615","gameId":4000000000,"title":"Patch",
			"patchedOn":"2026-09-16","publishedAt":"2026-09-15T15:30:00Z",
			"body":"Balance\\n- HP +20%","bodyFormat":"PLAIN_TEXT","url":null}
			"""));
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	void missingGameStopsBeforePatchLookup() throws Exception {
		assertError(request("0", PATCH_ID), 404, "GAME_NOT_FOUND");
		verify(repository, never()).find(anyLong(), anyString());
	}

	@Test
	void missingPatchOrPatchFromAnotherGameReturns404() throws Exception {
		when(repository.gameExists(GAME_ID)).thenReturn(true);
		assertError(request(Long.toString(GAME_ID), "another-game-patch"), 404, "PATCH_NOT_FOUND");
		verify(repository).find(GAME_ID, "another-game-patch");
	}

	@Test
	void malformedGameIdReturns400BeforeLookup() throws Exception {
		assertError(request("9223372036854775808", PATCH_ID), 400, "INVALID_REQUEST");
		verifyNoInteractions(repository);
	}

	@Test
	void anonymousInvalidTokenAndInactiveMemberCannotRead() throws Exception {
		assertError(mvc.perform(get(PATH, GAME_ID, PATCH_ID)), 401, "UNAUTHORIZED")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		assertError(mvc.perform(get(PATH, GAME_ID, PATCH_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer invalid")), 401, "UNAUTHORIZED");
		when(memberAccessService.isActive(1L)).thenReturn(false);
		assertError(request(Long.toString(GAME_ID), PATCH_ID), 401, "UNAUTHORIZED");
		verifyNoInteractions(repository);
	}

	@Test
	void hidesDatabaseFailureDetails() throws Exception {
		when(repository.gameExists(GAME_ID)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
		assertError(request(Long.toString(GAME_ID), PATCH_ID), 500, "INTERNAL_SERVER_ERROR");
	}

	private ResultActions request(String gameId, String patchId) throws Exception {
		return mvc.perform(get(PATH, gameId, patchId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(1L)));
	}

	private ResultActions assertError(ResultActions result, int expectedStatus, String code) throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		assertThat(body.toString()).doesNotContain("private-db-detail", "Exception");
		return result;
	}
}
