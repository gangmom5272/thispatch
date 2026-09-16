package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Map;

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
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.LogoutService;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(LogoutController.class)
@Import({LogoutService.class, RefreshTokenService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class LogoutControllerTest extends com.ssafy.thispatch.support.ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String PATH = "/auth/logout";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JwtProperties properties;
	@MockitoBean
	private MemberRepository repository;

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void returnsExactSuccessForCurrentOrAlreadyClearedToken(int affectedRows) throws Exception {
		when(repository.clearRefreshToken(eq(MEMBER_ID), anyString())).thenReturn(affectedRows);
		String refresh = tokens.issueRefreshToken(MEMBER_ID);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var response = request(mapper.writeValueAsString(Map.of("refreshToken", refresh, "memberId", 99)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("로그아웃되었습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(response.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.has("data")).isTrue();
		assertThat(body.get("data").isNull()).isTrue();
		Instant respondedAt = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(response.getRequest().getSession(false)).isNull();
		verify(repository).clearRefreshToken(eq(MEMBER_ID), anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}",
		"{\"refreshToken\":\"   \"}", "{\"refreshToken\":\"\\t\\r\\n\"}"})
	void missingNullOrBlankTokenReturnsFieldError(String body) throws Exception {
		assertError(request(body), 400, "VALIDATION_FAILED", "입력값을 확인해주세요.", true)
			.andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
			.andExpect(jsonPath("$.errors[0].message").value("Refresh Token을 입력해주세요."));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]", "{\"refreshToken\":123}", "{\"refreshToken\":true}",
		"{\"refreshToken\":{}}", "{\"refreshToken\":[]}"})
	void missingMalformedOrNonStringBodyReturnsInvalidRequest(String body) throws Exception {
		assertError(request(body), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.", false);
		verifyNoInteractions(repository);
	}

	@Test
	void missingAccessTokenIsRejectedBeforeReadingBody() throws Exception {
		assertUnauthorized(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{")));
		verifyNoInteractions(repository);
	}

	@Test
	void invalidExpiredOrWrongPurposeAccessTokenCannotLogout() throws Exception {
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		for (String token : List.of("invalid-token", tokens.issueRefreshToken(MEMBER_ID),
			past.issueAccessToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"private-token\"}")));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void duplicateAuthorizationIsRejected() throws Exception {
		String authorization = "Bearer " + tokens.issueAccessToken(MEMBER_ID);
		assertUnauthorized(mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, authorization, authorization)
			.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"private-token\"}")));
		verifyNoInteractions(repository);
	}

	@Test
	void allRefreshValidationFailuresReturnSameErrorWithoutDatabaseAccess() throws Exception {
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		var otherKey = new JwtProperties(
			"b3RoZXItdGVzdC1zZWNyZXQtbmV2ZXItdXNlLWluLXByb2Q=", "HS256",
			Duration.ofMinutes(15), Duration.ofDays(7));
		var forged = new JwtTokenProvider(otherKey, Clock.systemUTC());
		for (String refresh : List.of("private-invalid-token", tokens.issueRefreshToken(MEMBER_ID + 1),
			tokens.issueAccessToken(MEMBER_ID), past.issueRefreshToken(MEMBER_ID),
			forged.issueRefreshToken(MEMBER_ID))) {
			var result = assertError(request(mapper.writeValueAsString(Map.of("refreshToken", refresh))),
				401, "LOGOUT_TOKEN_INVALID", "유효하지 않은 Refresh Token입니다.", false)
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")).andReturn();
			assertThat(result.getResponse().getContentAsString()).doesNotContain(refresh);
		}
		verifyNoInteractions(repository);
	}

	@Test
	void storageFailureReturnsServerErrorWithoutExposingDetails() throws Exception {
		when(repository.clearRefreshToken(eq(MEMBER_ID), anyString()))
			.thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertError(request(mapper.writeValueAsString(Map.of("refreshToken", tokens.issueRefreshToken(MEMBER_ID)))),
			500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", false);
	}

	private ResultActions request(String body) throws Exception {
		return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(MEMBER_ID))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result, 401, "UNAUTHORIZED", "인증이 필요합니다.", false)
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	private ResultActions assertError(ResultActions result, int status, String code, String message, boolean fieldError)
		throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(fieldError ? 4 : 3);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.has("errors")).isEqualTo(fieldError);
		assertThat(body.toString()).doesNotContain("private database detail", "private-token",
			"rejectedValue", "DataAccessResourceFailureException", "TokenValidationException");
		if (fieldError) {
			body.get("errors").forEach(error -> assertThat(error.size()).isEqualTo(2));
		}
		return result;
	}
}
