package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.repository.MyGameUnregisterRepository;
import com.ssafy.thispatch.domain.game.service.MyGameUnregisterService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(MyGameUnregisterController.class)
@Import({MyGameUnregisterService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MyGameUnregisterControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	private static final String PATH = "/games/{gameId}/my-game";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@MockitoBean private MyGameUnregisterRepository repository;

	@Test
	void returnsExactSuccessAndUsesAuthenticatedMemberAndLongGameId() throws Exception {
		when(repository.gameExists(GAME_ID)).thenReturn(true);
		when(repository.deleteRegistration(MEMBER_ID, GAME_ID)).thenReturn(1);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = mvc.perform(delete(PATH, GAME_ID)
				.header(HttpHeaders.AUTHORIZATION, authorization())
				.param("memberId", "99").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":99}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.has("data")).isFalse();
		Instant respondedAt = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).deleteRegistration(MEMBER_ID, GAME_ID);
	}

	@Test
	void missingGameReturnsGameNotFoundWithoutDeleting() throws Exception {
		assertError(request(GAME_ID), 404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.");
		verify(repository).gameExists(GAME_ID);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void noDeletedRowReturnsUnregisteredError() throws Exception {
		when(repository.gameExists(GAME_ID)).thenReturn(true);
		assertError(request(GAME_ID), 404, "MY_GAME_NOT_REGISTERED", "내 게임에 등록되지 않은 게임입니다.");
		verify(repository).deleteRegistration(MEMBER_ID, GAME_ID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"private-invalid-game", "9223372036854775808", "-9223372036854775809", "1.5"})
	void invalidLongReturnsCommonBadRequest(String gameId) throws Exception {
		assertError(request(gameId), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.");
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE})
	void validLongValuesUseGameExistenceWithoutInventingRangeValidation(long gameId) throws Exception {
		assertError(request(gameId), 404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.");
		verify(repository).gameExists(gameId);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void missingInvalidExpiredAndWrongPurposeTokensAreRejected() throws Exception {
		assertUnauthorized(mvc.perform(delete(PATH, GAME_ID)));
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		for (String token : List.of("private-invalid-token", past.issueAccessToken(MEMBER_ID),
			tokens.issueRefreshToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(delete(PATH, GAME_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
		}
		assertUnauthorized(mvc.perform(delete(PATH, GAME_ID)
			.header(HttpHeaders.AUTHORIZATION, authorization(), authorization())));
		verifyNoInteractions(repository);
	}

	@Test
	void inactiveMemberCannotUnregister() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertUnauthorized(request(GAME_ID));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void databaseFailureDuringLookupOrDeleteRemainsServerError(boolean duringDelete) throws Exception {
		var failure = new DataAccessResourceFailureException("private database detail");
		if (duringDelete) {
			when(repository.gameExists(GAME_ID)).thenReturn(true);
			when(repository.deleteRegistration(MEMBER_ID, GAME_ID)).thenThrow(failure);
		} else {
			when(repository.gameExists(GAME_ID)).thenThrow(failure);
		}
		assertError(request(GAME_ID), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private ResultActions request(Object gameId) throws Exception {
		return mvc.perform(delete(PATH, gameId).header(HttpHeaders.AUTHORIZATION, authorization()));
	}

	private String authorization() {
		return "Bearer " + tokens.issueAccessToken(MEMBER_ID);
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result, 401, "UNAUTHORIZED", "인증이 필요합니다.")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	private ResultActions assertError(ResultActions result, int statusCode, String code, String message) throws Exception {
		result.andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist());
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.toString()).doesNotContain("private database detail", "private-invalid",
			"DataAccessResourceFailureException", "rejectedValue", "java.lang");
		return result;
	}
}
