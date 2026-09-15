package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.CurrentMemberService;
import com.ssafy.thispatch.domain.member.service.SessionService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(SessionController.class)
@Import({SessionService.class, CurrentMemberService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class SessionControllerTest {

	private static final long MEMBER_ID = 3_000_000_000L;

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JwtProperties properties;
	@Autowired
	private ObjectMapper objectMapper;
	@MockitoBean
	private MemberRepository repository;

	@Test
	void missingTokenReturnsAnonymousWithoutQueryingDatabase() throws Exception {
		assertAnonymous(mvc.perform(get("/session")));
		verifyNoInteractions(repository);
	}

	@Test
	void expiredAccessTokenReturnsAnonymousWithoutQueryingDatabase() throws Exception {
		assertAnonymous(session(pastTokens().issueAccessToken(MEMBER_ID)));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"회원 닉네임"})
	void activeMemberReturnsOnlyIdAndNullableNickname(String nickname) throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member("ACTIVE", nickname)));
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = session(tokens.issueAccessToken(MEMBER_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.authenticated").value(true))
			.andExpect(jsonPath("$.data.user.id").value(MEMBER_ID))
			.andExpect(jsonPath("$.data.user.nickname").value(nickname))
			.andReturn();

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("data").size()).isEqualTo(2);
		assertThat(body.path("data").path("user").size()).isEqualTo(2);
		assertThat(body.path("data").path("user").has("nickname")).isTrue();
		Instant responseTime = LocalDateTime.parse(body.path("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(responseTime).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).findById(MEMBER_ID);
	}

	@Test
	void missingMemberReturnsAnonymous() throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.empty());
		assertAnonymous(session(tokens.issueAccessToken(MEMBER_ID)));
		verify(repository).findById(MEMBER_ID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"WITHDRAWN", "INACTIVE", "UNKNOWN", "active"})
	void nonActiveMemberReturnsAnonymous(String memberStatus) throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(memberStatus, "회원")));
		assertAnonymous(session(tokens.issueAccessToken(MEMBER_ID)));
		verify(repository).findById(MEMBER_ID);
	}

	@Test
	void memberStateIsReadAgainForEachRequestWithSameToken() throws Exception {
		String token = tokens.issueAccessToken(MEMBER_ID);
		when(repository.findById(MEMBER_ID))
			.thenReturn(Optional.of(member("ACTIVE", "회원")))
			.thenReturn(Optional.of(member("WITHDRAWN", "회원")));
		session(token).andExpect(jsonPath("$.data.authenticated").value(true));
		assertAnonymous(session(token));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Bearer", "Bearer ", "Basic abc", "Bearer\tabc", "Bearer invalid-token",
		"Bearer a.b.c", "Bearer abc, Bearer def", "Bearer abc def", "Bearer abc "})
	void malformedAuthorizationReturnsUnauthorizedWithoutMemberLookup(String authorization) throws Exception {
		assertUnauthorized(mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, authorization)));
		verifyNoInteractions(repository);
	}

	@Test
	void duplicateAuthorizationIsRejected() throws Exception {
		String token = "Bearer " + tokens.issueAccessToken(MEMBER_ID);
		assertUnauthorized(mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, token, token)));
		verifyNoInteractions(repository);
	}

	@Test
	void forgedAccessTokenIncludingExpiredOneIsRejected() throws Exception {
		for (String token : List.of(tokens.issueAccessToken(MEMBER_ID), pastTokens().issueAccessToken(MEMBER_ID))) {
			int start = token.lastIndexOf('.') + 1;
			String forged = token.substring(0, start) + (token.charAt(start) == 'A' ? 'B' : 'A')
				+ token.substring(start + 1);
			assertUnauthorized(session(forged));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void refreshTokensIncludingExpiredOneAreRejected() throws Exception {
		assertUnauthorized(session(tokens.issueRefreshToken(MEMBER_ID)));
		assertUnauthorized(session(pastTokens().issueRefreshToken(MEMBER_ID)));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@MethodSource("databaseFailures")
	void databaseFailureReturnsServiceUnavailableWithCommonErrorBody(RuntimeException failure) throws Exception {
		when(repository.findById(MEMBER_ID)).thenThrow(failure);
		assertError(session(tokens.issueAccessToken(MEMBER_ID)).andExpect(status().isServiceUnavailable()),
			"SESSION_UNAVAILABLE", "세션 정보를 조회할 수 없습니다.");
	}

	static Stream<RuntimeException> databaseFailures() {
		return Stream.of(new DataAccessResourceFailureException("private database details"),
			new CannotCreateTransactionException("private transaction details"));
	}

	@Test
	void unexpectedFailureKeepsInternalServerError() throws Exception {
		when(repository.findById(MEMBER_ID)).thenThrow(new IllegalStateException("private internal details"));
		assertError(session(tokens.issueAccessToken(MEMBER_ID)).andExpect(status().isInternalServerError()),
			"INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private Member member(String memberStatus, String nickname) {
		Member member = Member.builder().loginType(nickname == null ? LoginType.STEAM : LoginType.LOCAL)
			.nickname(nickname).status(memberStatus).email("private@example.com").password("private-password")
			.createdAt(Instant.now()).build();
		ReflectionTestUtils.setField(member, "memberId", MEMBER_ID);
		return member;
	}

	private JwtTokenProvider pastTokens() {
		return new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
	}

	private ResultActions session(String token) throws Exception {
		return mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private void assertAnonymous(ResultActions result) throws Exception {
		var response = result.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.authenticated").value(false))
			.andExpect(jsonPath("$.data.user").value(nullValue()))
			.andReturn().getResponse();
		JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("data").size()).isEqualTo(2);
		assertThat(body.path("data").has("user")).isTrue();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")),
			"UNAUTHORIZED", "인증이 필요합니다.");
	}

	private void assertError(ResultActions result, String code, String message) throws Exception {
		var response = result.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn().getResponse();
		JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
	}
}
