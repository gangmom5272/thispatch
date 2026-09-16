package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.repository.MyGameRegistrationRepository;
import com.ssafy.thispatch.domain.game.service.MyGameRegistrationService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(MyGameRegistrationController.class)
@Import({MyGameRegistrationService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MyGameRegistrationControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	private static final String PATH = "/games/{gameId}/my-game";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@MockitoBean private MyGameRegistrationRepository repository;

	@Test
	void registersAuthenticatedMemberWithExactSuccessEnvelopeAndLongIdentifiers() throws Exception {
		when(repository.lockExistingGame(GAME_ID)).thenReturn(true);
		when(repository.insertIfAbsent(MEMBER_ID, GAME_ID)).thenReturn(1);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = request(Long.toString(GAME_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		Instant respondedAt = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).insertIfAbsent(MEMBER_ID, GAME_ID);
	}

	@Test
	void clientSuppliedMemberIdCannotChooseRegistrationOwner() throws Exception {
		when(repository.lockExistingGame(GAME_ID)).thenReturn(true);
		when(repository.insertIfAbsent(MEMBER_ID, GAME_ID)).thenReturn(1);
		mvc.perform(post(PATH, GAME_ID).queryParam("memberId", "99")
			.header(HttpHeaders.AUTHORIZATION, authorization())
			.contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":99}"))
			.andExpect(status().isOk());
		verify(repository).insertIfAbsent(MEMBER_ID, GAME_ID);
		verify(repository, never()).insertIfAbsent(99L, GAME_ID);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, 4_000_000_000L, Long.MAX_VALUE})
	void missingGameReturns404WithoutInserting(long gameId) throws Exception {
		assertError(request(Long.toString(gameId)), 404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.");
		verify(repository, never()).insertIfAbsent(anyLong(), anyLong());
	}

	@Test
	void duplicateReturns409() throws Exception {
		when(repository.lockExistingGame(GAME_ID)).thenReturn(true);
		when(repository.insertIfAbsent(MEMBER_ID, GAME_ID)).thenReturn(0);
		assertError(request(Long.toString(GAME_ID)), 409, "MY_GAME_ALREADY_REGISTERED",
			"이미 내 게임으로 등록된 게임입니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {"private-invalid-id", "9223372036854775808", "1.5"})
	void malformedOrOverflowingIdReturnsSafe400(String gameId) throws Exception {
		assertError(request(gameId), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.");
		verifyNoInteractions(repository);
	}

	@Test
	void missingAuthenticationIsRejectedBeforeGameLookup() throws Exception {
		assertUnauthorized(mvc.perform(post(PATH, GAME_ID)));
		verifyNoInteractions(repository);
	}

	@Test
	void invalidExpiredAndRefreshTokensCannotRegister() throws Exception {
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
		for (String token : List.of("private-invalid-token", past.issueAccessToken(MEMBER_ID),
			tokens.issueRefreshToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(post(PATH, GAME_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void duplicateAuthorizationIsRejected() throws Exception {
		String authorization = authorization();
		assertUnauthorized(mvc.perform(post(PATH, GAME_ID)
			.header(HttpHeaders.AUTHORIZATION, authorization, authorization)));
		verifyNoInteractions(repository);
	}

	@Test
	void inactiveOrMissingMemberCannotRegister() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertUnauthorized(request(Long.toString(GAME_ID)));
		verifyNoInteractions(repository);
	}

	@Test
	void gameLookupFailureReturns500WithoutInserting() throws Exception {
		when(repository.lockExistingGame(GAME_ID))
			.thenThrow(new DataAccessResourceFailureException("private-database-detail"));
		assertError(request(Long.toString(GAME_ID)), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
		verify(repository, never()).insertIfAbsent(anyLong(), anyLong());
	}

	@Test
	void unrelatedIntegrityFailureIsNotReportedAsDuplicate() throws Exception {
		when(repository.lockExistingGame(GAME_ID)).thenReturn(true);
		when(repository.insertIfAbsent(MEMBER_ID, GAME_ID))
			.thenThrow(new DataIntegrityViolationException("private-database-detail"));
		assertError(request(Long.toString(GAME_ID)), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private String authorization() {
		return "Bearer " + tokens.issueAccessToken(MEMBER_ID);
	}

	private ResultActions request(String gameId) throws Exception {
		return mvc.perform(post(PATH, gameId).header(HttpHeaders.AUTHORIZATION, authorization()));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result, 401, "UNAUTHORIZED", "인증이 필요합니다.")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	private ResultActions assertError(ResultActions result, int expectedStatus, String code, String message)
		throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		assertThat(body.has("errors")).isFalse();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.toString()).doesNotContain("private-", "Exception", "rejectedValue", "java.lang");
		return result;
	}
}
