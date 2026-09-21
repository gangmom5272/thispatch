package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.dto.PasswordChangeRequest;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.PasswordChangeService;
import com.ssafy.thispatch.global.config.AppConfig;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(PasswordChangeController.class)
@Import({PasswordChangeService.class, AppConfig.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PasswordChangeControllerTest extends com.ssafy.thispatch.support.ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String PATH = "/members/me/password";
	private static final String CURRENT = "  Old-private-password  ";
	private static final String NEXT = "  New-private-password  ";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@Autowired private PasswordEncoder encoder;
	@MockitoBean private MemberRepository repository;

	@Test
	void changesOnlyAuthenticatedMemberWithExactEnvelopeAndUnmodifiedPassword() throws Exception {
		localMember(CURRENT);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var result = request(mapper.writeValueAsString(Map.of("currentPassword", CURRENT, "newPassword", NEXT,
			"memberId", 99, "refreshToken", "ignored-token", "newPasswordConfirm", "ignored-confirmation")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다."))
			.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		Instant respondedAt = LocalDateTime.parse(body.path("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
		var hash = ArgumentCaptor.forClass(String.class);
		verify(repository).changePasswordAndClearRefreshToken(eq(MEMBER_ID), hash.capture(), any());
		assertThat(encoder.matches(NEXT, hash.getValue())).isTrue();
		assertThat(encoder.matches(NEXT.strip(), hash.getValue())).isFalse();
		assertThat(body.toString()).doesNotContain(CURRENT, NEXT, hash.getValue(), "ignored-token");
	}

	@ParameterizedTest
	@MethodSource("validPasswords")
	void acceptsSignupPolicyBoundariesForBothFields(String password) throws Exception {
		localMember(password);
		change(password, NEXT).andExpect(status().isOk());
		localMember(CURRENT);
		change(CURRENT, password).andExpect(status().isOk());
	}

	static Stream<String> validPasswords() {
		return Stream.of("a", "a".repeat(72), "가".repeat(24), "🎮".repeat(18), "  Mixed Case  ");
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void invalidFieldsNeverReachPasswordOrTokenStorage(String field, Object value) throws Exception {
		var body = mapper.createObjectNode().put("currentPassword", CURRENT).put("newPassword", NEXT);
		if (value == null) {
			body.putNull(field);
		} else {
			body.put(field, value.toString());
		}
		assertError(request(body.toString()), 400, "VALIDATION_FAILED", "입력값을 확인해주세요.", field);
		verifyNoInteractions(repository);
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of("currentPassword", "newPassword").flatMap(field ->
			Stream.of(null, "", " \t\r\n", "a".repeat(73), "가".repeat(25), "🎮".repeat(19))
				.map(value -> Arguments.of(field, value)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"currentPassword", "newPassword"})
	void missingFieldIsValidationFailure(String field) throws Exception {
		var body = mapper.createObjectNode().put("currentPassword", CURRENT).put("newPassword", NEXT);
		body.remove(field);
		assertError(request(body.toString()), 400, "VALIDATION_FAILED", "입력값을 확인해주세요.", field);
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]", "{\"currentPassword\":123,\"newPassword\":\"new\"}",
		"{\"currentPassword\":true,\"newPassword\":\"new\"}", "{\"currentPassword\":{},\"newPassword\":\"new\"}",
		"{\"currentPassword\":[],\"newPassword\":\"new\"}", "{\"currentPassword\":\"old\",\"newPassword\":123}",
		"{\"currentPassword\":\"old\",\"newPassword\":true}", "{\"currentPassword\":\"old\",\"newPassword\":{}}",
		"{\"currentPassword\":\"old\",\"newPassword\":[]}"})
	void malformedBodyOrNonStringFieldIsInvalidRequest(String body) throws Exception {
		assertError(request(body), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.", null);
		verifyNoInteractions(repository);
	}

	@Test
	void verifiesCurrentPasswordBeforeRejectingIdenticalNewPassword() throws Exception {
		localMember(CURRENT);
		assertError(change("wrong-private-password", "wrong-private-password"), 400,
			"CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다.", null);
		assertError(change(CURRENT.strip(), NEXT), 400,
			"CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다.", null);
		assertError(change(CURRENT, CURRENT), 400, "VALIDATION_FAILED", "입력값을 확인해주세요.", "newPassword")
			.andExpect(jsonPath("$.errors[0].message").value("새 비밀번호는 현재 비밀번호와 달라야 합니다."));
		verify(repository, never()).changePasswordAndClearRefreshToken(any(Long.class), anyString(), any());
	}

	@Test
	void steamAccountIsRejectedBeforePasswordComparison() throws Exception {
		when(repository.findByIdForPasswordChange(MEMBER_ID)).thenReturn(Optional.of(
			Member.builder().loginType(LoginType.STEAM).status("ACTIVE").build()));
		assertError(change(CURRENT, CURRENT), 409,
			"PASSWORD_CHANGE_NOT_SUPPORTED", "비밀번호를 사용하는 계정이 아닙니다.", null);
		verify(repository, never()).changePasswordAndClearRefreshToken(any(Long.class), anyString(), any());
	}

	@Test
	void requiresValidAccessTokenBeforeReadingBody() throws Exception {
		assertUnauthorized(mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{")));
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		for (String token : List.of("invalid-token", tokens.issueRefreshToken(MEMBER_ID), past.issueAccessToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{")));
		}
		String bearer = "Bearer " + tokens.issueAccessToken(MEMBER_ID);
		assertUnauthorized(mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, bearer, bearer)
			.contentType(MediaType.APPLICATION_JSON).content("{")));
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsInactiveMemberInFilterAndMissingOrInactiveMemberAfterAuthentication() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertUnauthorized(request("{"));
		verifyNoInteractions(repository);
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(true);
		assertUnauthorized(change(CURRENT, NEXT));
		when(repository.findByIdForPasswordChange(MEMBER_ID)).thenReturn(Optional.of(
			Member.builder().loginType(LoginType.LOCAL).status("WITHDRAWN").build()));
		assertUnauthorized(change(CURRENT, NEXT));
		verify(repository, never()).changePasswordAndClearRefreshToken(any(Long.class), anyString(), any());
	}

	@Test
	void databaseFailuresUseSafeInternalServerError() throws Exception {
		when(repository.findByIdForPasswordChange(MEMBER_ID))
			.thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertError(change(CURRENT, NEXT), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", null);
	}

	@Test
	void databaseWriteFailureUsesSafeInternalServerError() throws Exception {
		localMember(CURRENT);
		when(repository.changePasswordAndClearRefreshToken(eq(MEMBER_ID), anyString(), any()))
			.thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertError(change(CURRENT, NEXT), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", null);
	}

	@Test
	void requestToStringRedactsBothPasswords() {
		assertThat(new PasswordChangeRequest(CURRENT, NEXT).toString()).doesNotContain(CURRENT, NEXT);
	}

	private void localMember(String password) {
		when(repository.findByIdForPasswordChange(MEMBER_ID)).thenReturn(Optional.of(
			Member.builder().loginType(LoginType.LOCAL).status("ACTIVE").password(encoder.encode(password)).build()));
		when(repository.changePasswordAndClearRefreshToken(eq(MEMBER_ID), anyString(), any())).thenReturn(1);
	}

	private ResultActions change(String current, String next) throws Exception {
		return request(mapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next)));
	}

	private ResultActions request(String body) throws Exception {
		return mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(MEMBER_ID))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")),
			401, "UNAUTHORIZED", "인증이 필요합니다.", null);
	}

	private ResultActions assertError(ResultActions result, int status, String code, String message, String field)
		throws Exception {
		var response = result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist()).andReturn();
		var body = mapper.readTree(response.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(field == null ? 3 : 4);
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		if (field != null) {
			assertThat(body.path("errors").isEmpty()).isFalse();
			body.path("errors").forEach(error -> {
				assertThat(error.size()).isEqualTo(2);
				assertThat(error.path("field").asText()).isEqualTo(field);
			});
		} else {
			assertThat(body.has("errors")).isFalse();
		}
		assertThat(body.toString()).doesNotContain(CURRENT, NEXT, "wrong-private-password", "private database detail",
			"rejectedValue", "DataAccessResourceFailureException");
		return result;
	}
}
