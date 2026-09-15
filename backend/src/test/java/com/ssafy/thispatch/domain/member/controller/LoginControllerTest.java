package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.dto.LoginRequest;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.LoginService;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.global.config.AppConfig;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(LoginController.class)
@Import({LoginService.class, AppConfig.class, JwtConfig.class, SecurityConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class LoginControllerTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "private-password";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private PasswordEncoder encoder;
	@MockitoSpyBean
	private JwtTokenProvider tokens;
	@MockitoBean
	private MemberRepository members;
	@MockitoBean
	private RefreshTokenService refresh;

	@Test
	void anonymousLoginNormalizesEmailAndReturnsOnlyContractTokens() throws Exception {
		when(members.findByEmail(EMAIL)).thenReturn(Optional.of(member("ACTIVE", LoginType.LOCAL, PASSWORD)));
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer ignored-invalid-token")
			.content(body(" \tUser@Example.COM\n ", PASSWORD)))
			.andExpect(status().isOk()).andReturn().getResponse();
		JsonNode result = mapper.readTree(response.getContentAsByteArray());
		assertThat(result.size()).isEqualTo(5);
		assertThat(result.get("code").asText()).isEqualTo("200");
		assertThat(result.get("message").asText()).isEqualTo("성공했습니다.");
		assertThat(result.get("success").asBoolean()).isTrue();
		assertThat(result.get("data").size()).isEqualTo(2);
		Instant respondedAt = LocalDateTime.parse(result.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		String access = result.at("/data/accessToken").asText();
		String refreshToken = result.at("/data/refreshToken").asText();
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(MEMBER_ID);
		assertThat(tokens.validateRefreshToken(refreshToken).memberId()).isEqualTo(MEMBER_ID);
		verify(refresh).store(MEMBER_ID, refreshToken);
		verify(members).findByEmail(EMAIL);
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertThat(result.toString()).doesNotContain(PASSWORD, EMAIL, "nickname");
	}

	@ParameterizedTest
	@MethodSource("validPasswords")
	void acceptsPasswordBoundariesWithoutTrimmingOrComplexityRules(String password) throws Exception {
		when(members.findByEmail(EMAIL)).thenReturn(Optional.of(member("ACTIVE", LoginType.LOCAL, password)));
		mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(EMAIL, password)))
			.andExpect(status().isOk());
	}

	static Stream<String> validPasswords() {
		return Stream.of("x", "a".repeat(72), "한".repeat(24), "😀".repeat(18), " Password ");
	}

	@ParameterizedTest
	@MethodSource("invalidPasswords")
	void rejectsBlankOrOversizedPasswordsAsFieldErrors(String password) throws Exception {
		JsonNode error = error(body(EMAIL, password), 400, "VALIDATION_FAILED");
		assertThat(error.get("errors").findValuesAsText("field")).contains("password");
		verifyNoInteractions(members, refresh);
	}

	static Stream<String> invalidPasswords() {
		return Stream.of("", " \t\n", "a".repeat(73), "한".repeat(25), "😀".repeat(19), "a".repeat(71) + "é");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"email\":null,\"password\":null}",
		"{\"email\":\"user@example.com\"}", "{\"password\":\"private-password\"}"})
	void missingFieldsAreValidationErrors(String body) throws Exception {
		assertThat(error(body, 400, "VALIDATION_FAILED").get("errors")).isNotEmpty();
		verifyNoInteractions(members, refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \t ", "not-an-email", "user @example.com", "user@example .com"})
	void invalidEmailIsAFieldError(String email) throws Exception {
		assertThat(error(body(email, PASSWORD), 400, "VALIDATION_FAILED")
			.get("errors").findValuesAsText("field")).contains("email");
		verifyNoInteractions(members, refresh);
	}

	@Test
	void emailLengthIsCheckedAfterNormalization() throws Exception {
		String email = emailWithLength(255);
		when(members.findByEmail(email)).thenReturn(Optional.of(member("ACTIVE", LoginType.LOCAL, PASSWORD)));
		mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(body(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD)))
			.andExpect(status().isOk());
		assertThat(error(body(emailWithLength(256), PASSWORD), 400, "VALIDATION_FAILED")
			.get("errors").findValuesAsText("field")).contains("email");
	}

	@Test
	void preservesDotsAndPlusAliases() throws Exception {
		String email = "first.last+tag@gmail.com";
		when(members.findByEmail(email)).thenReturn(Optional.empty());
		error(body(" FIRST.LAST+TAG@GMAIL.COM ", PASSWORD), 401, "LOGIN_FAILED");
		verify(members).findByEmail(email);
		verifyNoInteractions(refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]", "{\"email\":123,\"password\":\"x\"}",
		"{\"email\":true,\"password\":\"x\"}", "{\"email\":[],\"password\":\"x\"}",
		"{\"email\":{},\"password\":\"x\"}", "{\"email\":\"user@example.com\",\"password\":123}",
		"{\"email\":\"user@example.com\",\"password\":false}",
		"{\"email\":\"user@example.com\",\"password\":[]}",
		"{\"email\":\"user@example.com\",\"password\":{}}"})
	void malformedBodyAndNonStringFieldsAreInvalidRequests(String body) throws Exception {
		assertThat(error(body, 400, "INVALID_REQUEST").size()).isEqualTo(3);
		verifyNoInteractions(members, refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WRONG_PASSWORD", "WITHDRAWN", "UNKNOWN", "STEAM", "NO_PASSWORD", "BAD_HASH"})
	void invalidCredentialsAndIneligibleMembersShareFailureWithoutIssuingTokens(String reason) throws Exception {
		Member member = member(reason.equals("WITHDRAWN") || reason.equals("UNKNOWN") ? reason : "ACTIVE",
			reason.equals("STEAM") ? LoginType.STEAM : LoginType.LOCAL, PASSWORD);
		if (reason.equals("NO_PASSWORD") || reason.equals("BAD_HASH")) {
			ReflectionTestUtils.setField(member, "password", reason.equals("NO_PASSWORD") ? null : "not-a-hash");
		}
		when(members.findByEmail(EMAIL)).thenReturn(reason.equals("MISSING") ? Optional.empty() : Optional.of(member));
		String input = reason.equals("WRONG_PASSWORD") ? PASSWORD.toUpperCase(java.util.Locale.ROOT) : PASSWORD;
		JsonNode result = error(body(EMAIL, input), 401, "LOGIN_FAILED");
		assertThat(result.get("message").asText()).isEqualTo("이메일 또는 비밀번호가 일치하지 않습니다.");
		assertThat(result.size()).isEqualTo(3);
		verifyNoInteractions(refresh);
		org.mockito.Mockito.verify(tokens, org.mockito.Mockito.never()).issueAccessToken(anyLong());
		org.mockito.Mockito.verify(tokens, org.mockito.Mockito.never()).issueRefreshToken(anyLong());
	}

	@ParameterizedTest
	@ValueSource(strings = {"LOOKUP", "ISSUE", "STORE"})
	void infrastructureFailuresAreSafeServerErrors(String stage) throws Exception {
		var failure = new DataAccessResourceFailureException("private-infrastructure-detail");
		if (stage.equals("LOOKUP")) {
			when(members.findByEmail(EMAIL)).thenThrow(failure);
		} else {
			when(members.findByEmail(EMAIL)).thenReturn(Optional.of(member("ACTIVE", LoginType.LOCAL, PASSWORD)));
			if (stage.equals("ISSUE")) {
				doThrow(failure).when(tokens).issueRefreshToken(MEMBER_ID);
			} else {
				doThrow(failure).when(refresh).store(anyLong(), anyString());
			}
		}
		JsonNode result = error(body(EMAIL, PASSWORD), 500, "INTERNAL_SERVER_ERROR");
		assertThat(result.size()).isEqualTo(3);
		assertThat(result.get("message").asText()).isEqualTo("서버 내부 오류가 발생했습니다.");
	}

	@Test
	void requestToStringDoesNotExposeCredentials() {
		assertThat(new LoginRequest(EMAIL, PASSWORD).toString()).doesNotContain(EMAIL, PASSWORD);
	}

	private JsonNode error(String body, int status, String code) throws Exception {
		var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(status)).andReturn().getResponse();
		JsonNode result = mapper.readTree(response.getContentAsByteArray());
		assertThat(result.get("code").asText()).isEqualTo(code);
		assertThat(result.get("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(result.has("data")).isFalse();
		assertThat(result.has("success")).isFalse();
		assertThat(result.toString()).doesNotContain(PASSWORD, EMAIL, "private-infrastructure-detail",
			"rejectedValue", "Exception", "accessToken", "refreshToken");
		if (code.equals("VALIDATION_FAILED")) {
			assertThat(result.get("message").asText()).isEqualTo("입력값을 확인해주세요.");
			result.get("errors").forEach(field -> assertThat(field.size()).isEqualTo(2));
		} else {
			assertThat(result.has("errors")).isFalse();
		}
		if (status == 401) {
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
		return result;
	}

	private String body(String email, String password) throws Exception {
		return mapper.writeValueAsString(Map.of("email", email, "password", password));
	}

	private Member member(String status, LoginType type, String password) {
		Member member = Member.builder().email(EMAIL).password(encoder.encode(password)).loginType(type)
			.status(status).nickname("회원").createdAt(Instant.now()).build();
		ReflectionTestUtils.setField(member, "memberId", MEMBER_ID);
		return member;
	}

	private String emailWithLength(int length) {
		return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(length - 193);
	}
}
