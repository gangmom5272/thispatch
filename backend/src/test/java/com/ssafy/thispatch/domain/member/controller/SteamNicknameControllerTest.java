package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.CurrentMemberService;
import com.ssafy.thispatch.domain.member.service.SteamNicknameService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(SteamNicknameController.class)
@Import({SteamNicknameService.class, CurrentMemberService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class SteamNicknameControllerTest extends com.ssafy.thispatch.support.ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String PATH = "/auth/steam/signup";

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
	@EnumSource(LoginType.class)
	void activeMemberSetsNicknameRegardlessOfLoginType(LoginType loginType) throws Exception {
		String nickname = "  한글 ! 🎮  ";
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(loginType, "ACTIVE", null)));
		when(repository.setNicknameIfUnset(eq(MEMBER_ID), eq(nickname), any())).thenReturn(1);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = request(mapper.writeValueAsString(Map.of("nickname", nickname, "memberId", 99, "steamId", "99")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("닉네임이 설정되었습니다."))
			.andExpect(jsonPath("$.data.nickname").value(nickname))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();

		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("data").size()).isEqualTo(1);
		Instant respondedAt = LocalDateTime.parse(body.path("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
		verify(repository).setNicknameIfUnset(eq(MEMBER_ID), eq(nickname), any());
		verify(repository, never()).findById(99L);
	}

	@ParameterizedTest
	@MethodSource("validNicknames")
	void acceptsOneToFiftyUnicodeCodePoints(String nickname) throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null)));
		when(repository.setNicknameIfUnset(eq(MEMBER_ID), eq(nickname), any())).thenReturn(1);
		request(mapper.writeValueAsString(Map.of("nickname", nickname)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(nickname));
	}

	static Stream<String> validNicknames() {
		return Stream.of("a", "가".repeat(50), "🎮".repeat(50));
	}

	@ParameterizedTest
	@MethodSource("invalidNicknames")
	void rejectsInvalidNicknameWithoutDatabaseAccess(String nickname) throws Exception {
		assertError(request(mapper.writeValueAsString(Map.of("nickname", nickname))), 400,
			"VALIDATION_FAILED", "입력값을 확인해주세요.", true);
		verifyNoInteractions(repository);
	}

	static Stream<String> invalidNicknames() {
		return Stream.of("", " \t\r\n", "\u2003", "\u00a0", "\u3000", "a".repeat(51), "🎮".repeat(51), "a\u0000b");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"nickname\":null}"})
	void missingOrNullNicknameReturnsFieldValidation(String body) throws Exception {
		assertError(request(body), 400, "VALIDATION_FAILED", "입력값을 확인해주세요.", true);
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]", "{\"nickname\":123}", "{\"nickname\":true}",
		"{\"nickname\":{}}", "{\"nickname\":[]}"})
	void malformedBodyAndNonStringNicknameAreInvalidRequests(String body) throws Exception {
		assertError(request(body), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.", false);
		verifyNoInteractions(repository);
	}

	@Test
	void missingTokenIsRejectedBeforeReadingBody() throws Exception {
		assertUnauthorized(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{")));
		verifyNoInteractions(repository);
	}

	@Test
	void expiredRefreshAndInvalidTokensCannotSetNickname() throws Exception {
		var pastTokens = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		for (String token : List.of("invalid-token", tokens.issueRefreshToken(MEMBER_ID),
			pastTokens.issueAccessToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"new\"}")));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void duplicateAuthorizationIsRejected() throws Exception {
		String authorization = "Bearer " + tokens.issueAccessToken(MEMBER_ID);
		assertUnauthorized(mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, authorization, authorization)
			.contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"new\"}")));
		verifyNoInteractions(repository);
	}

	@Test
	void missingMemberReturnsUnauthorized() throws Exception {
		assertUnauthorized(request("{\"nickname\":\"new\"}"));
		verify(repository, never()).setNicknameIfUnset(eq(MEMBER_ID), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"WITHDRAWN", "INACTIVE", "UNKNOWN", "active"})
	void inactiveMemberIsRejectedBeforeCheckingExistingNickname(String memberStatus) throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, memberStatus, "existing")));
		assertUnauthorized(request("{\"nickname\":\"new\"}"));
		verify(repository, never()).setNicknameIfUnset(eq(MEMBER_ID), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"existing", ""})
	void anyNonNullNicknameCannotBeReplaced(String nickname) throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", nickname)));
		assertConflict(request("{\"nickname\":\"new\"}"));
		verify(repository, never()).setNicknameIfUnset(eq(MEMBER_ID), any(), any());
	}

	@Test
	void concurrentWinnerMakesTheLosingRequestConflict() throws Exception {
		when(repository.findById(MEMBER_ID))
			.thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null)))
			.thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", "winner")));
		assertConflict(request("{\"nickname\":\"new\"}"));
		verify(repository).setNicknameIfUnset(eq(MEMBER_ID), eq("new"), any());
	}

	@Test
	void concurrentDeactivationReturnsUnauthorized() throws Exception {
		when(repository.findById(MEMBER_ID))
			.thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null)))
			.thenReturn(Optional.of(member(LoginType.STEAM, "INACTIVE", null)));
		assertUnauthorized(request("{\"nickname\":\"new\"}"));
	}

	@Test
	void concurrentDeletionReturnsUnauthorized() throws Exception {
		when(repository.findById(MEMBER_ID))
			.thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null))).thenReturn(Optional.empty());
		assertUnauthorized(request("{\"nickname\":\"new\"}"));
	}

	@Test
	void databaseReadFailureKeepsInternalServerError() throws Exception {
		when(repository.findById(MEMBER_ID)).thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertError(request("{\"nickname\":\"new\"}"), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", false);
	}

	@Test
	void databaseWriteFailureKeepsInternalServerError() throws Exception {
		when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null)));
		when(repository.setNicknameIfUnset(eq(MEMBER_ID), any(), any()))
			.thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertError(request("{\"nickname\":\"new\"}"), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", false);
	}

	private Member member(LoginType loginType, String memberStatus, String nickname) {
		return Member.builder().loginType(loginType).status(memberStatus).nickname(nickname).build();
	}

	private ResultActions request(String body) throws Exception {
		return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(MEMBER_ID))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")),
			401, "UNAUTHORIZED", "인증이 필요합니다.", false);
	}

	private void assertConflict(ResultActions result) throws Exception {
		assertError(result, 409, "NICKNAME_ALREADY_SET", "이미 닉네임이 설정된 회원입니다.", false);
	}

	private void assertError(ResultActions result, int status, String code, String message, boolean fieldError)
		throws Exception {
		var response = result.andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist()).andReturn();
		var body = mapper.readTree(response.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(fieldError ? 4 : 3);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		if (fieldError) {
			assertThat(body.path("errors").isEmpty()).isFalse();
			body.path("errors").forEach(error -> {
				assertThat(error.size()).isEqualTo(2);
				assertThat(error.path("field").asText()).isEqualTo("nickname");
				assertThat(error.path("message").asText()).isNotBlank();
			});
		} else {
			assertThat(body.has("errors")).isFalse();
		}
		assertThat(body.toString()).doesNotContain("private database detail", "rejectedValue", "DataAccessResourceFailureException");
	}
}
