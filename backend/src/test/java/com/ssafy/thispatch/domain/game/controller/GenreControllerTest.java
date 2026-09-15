package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.repository.TagRepository;
import com.ssafy.thispatch.domain.game.service.GenreService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(GenreController.class)
@Import({GenreService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class GenreControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@MockitoBean private TagRepository tags;

	@Test
	void emptyListReturnsSuccessEnvelopeAndKoreanTime() throws Exception {
		when(tags.findAllByOrderByTagIdAsc()).thenReturn(List.of());
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = authenticatedRequest()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.get("data").size()).isEqualTo(1);
		assertThat(body.at("/data/items").isArray()).isTrue();
		Instant responseTime = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(responseTime).isBetween(before, Instant.now());
	}

	@Test
	void missingAuthorizationDoesNotReadGenres() throws Exception {
		assertUnauthorized(mvc.perform(get("/genres")));
		verifyNoInteractions(tags, memberAccessService);
	}

	@Test
	void invalidExpiredAndRefreshTokensDoNotReadGenres() throws Exception {
		String expired = new JwtTokenProvider(properties,
			Clock.offset(Clock.systemUTC(), Duration.ofHours(-1))).issueAccessToken(MEMBER_ID);
		for (String token : List.of("invalid-token", expired, tokens.issueRefreshToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(get("/genres").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
		}
		verifyNoInteractions(tags, memberAccessService);
	}

	@Test
	void inactiveOrMissingMemberDoesNotReadGenres() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertUnauthorized(authenticatedRequest());
		verifyNoInteractions(tags);
	}

	@ParameterizedTest
	@MethodSource("databaseFailures")
	void genreDatabaseAndTransactionFailuresReturn503WithoutDetails(RuntimeException failure) throws Exception {
		when(tags.findAllByOrderByTagIdAsc()).thenThrow(failure);
		assertError(authenticatedRequest(), 503, "GENRE_LIST_UNAVAILABLE", "장르 목록을 조회할 수 없습니다.");
	}

	static Stream<RuntimeException> databaseFailures() {
		return Stream.of(
			new DataAccessResourceFailureException("private-database-detail"),
			new CannotCreateTransactionException("private-database-detail"),
			new TransactionSystemException("private-database-detail"));
	}

	@Test
	void unexpectedCodeFailureUsesCommon500() throws Exception {
		when(tags.findAllByOrderByTagIdAsc()).thenThrow(new IllegalStateException("private-code-detail"));
		assertError(authenticatedRequest(), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	@Test
	void authenticationDatabaseFailureRemains500() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID))
			.thenThrow(new DataAccessResourceFailureException("private-database-detail"));
		assertError(authenticatedRequest(), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
		verifyNoInteractions(tags);
	}

	private ResultActions authenticatedRequest() throws Exception {
		return mvc.perform(get("/genres")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(MEMBER_ID)));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		assertError(result, 401, "UNAUTHORIZED", "인증이 필요합니다.");
	}

	private void assertError(ResultActions result, int statusCode, String code, String message) throws Exception {
		var response = result.andExpect(status().is(statusCode))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn().getResponse();
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.toString()).doesNotContain("private-", "Exception");
	}
}
