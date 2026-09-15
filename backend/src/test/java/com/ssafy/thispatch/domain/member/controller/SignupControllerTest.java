package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.dto.SignupRequest;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.domain.member.service.SignupService;
import com.ssafy.thispatch.global.config.AppConfig;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(SignupController.class)
@Import({SignupService.class, AppConfig.class, JwtConfig.class, SecurityConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class SignupControllerTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = " private-password ";
	private static final String NICKNAME = " 회원 😀 ";
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
	void anonymousSignupNormalizesEmailAndReturnsOnlyContractTokens() throws Exception {
		prepareSuccess(EMAIL);
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var response = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer ignored-invalid-token")
			.content(body(" \tUser@Example.COM\n ", PASSWORD, NICKNAME)))
			.andExpect(status().isOk()).andReturn().getResponse();
		JsonNode result = mapper.readTree(response.getContentAsByteArray());
		assertThat(result.size()).isEqualTo(5);
		assertThat(result.get("code").asText()).isEqualTo("200");
		assertThat(result.get("message").asText()).isEqualTo("회원가입에 성공했습니다.");
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
		var hash = ArgumentCaptor.forClass(String.class);
		verify(members).insertLocalMemberIfAbsent(eq(EMAIL), hash.capture(), eq(NICKNAME));
		assertThat(encoder.matches(PASSWORD, hash.getValue())).isTrue();
		assertThat(encoder.matches(PASSWORD.strip(), hash.getValue())).isFalse();
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertThat(result.toString()).doesNotContain(PASSWORD, EMAIL, "nickname");
	}

	@ParameterizedTest
	@MethodSource("validPasswords")
	void acceptsPasswordBoundaries(String password) throws Exception {
		prepareSuccess(EMAIL);
		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content(body(EMAIL, password, NICKNAME))).andExpect(status().isOk());
		var hash = ArgumentCaptor.forClass(String.class);
		verify(members).insertLocalMemberIfAbsent(eq(EMAIL), hash.capture(), eq(NICKNAME));
		assertThat(encoder.matches(password, hash.getValue())).isTrue();
	}

	static Stream<String> validPasswords() {
		return Stream.of("x", "a".repeat(72), "한".repeat(24), "😀".repeat(18), " Password ");
	}

	@ParameterizedTest
	@MethodSource("invalidPasswords")
	void rejectsInvalidPasswords(String password) throws Exception {
		fieldError(body(EMAIL, password, NICKNAME), "password");
	}

	static Stream<String> invalidPasswords() {
		return Stream.of("", " \t\n", "a".repeat(73), "한".repeat(25), "😀".repeat(19), "a".repeat(71) + "é");
	}

	@ParameterizedTest
	@MethodSource("validNicknames")
	void acceptsNicknameBoundariesWithoutNormalization(String nickname) throws Exception {
		prepareSuccess(EMAIL);
		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content(body(EMAIL, PASSWORD, nickname))).andExpect(status().isOk());
		verify(members).insertLocalMemberIfAbsent(eq(EMAIL), anyString(), eq(nickname));
	}

	static Stream<String> validNicknames() {
		return Stream.of("x", "한".repeat(50), "😀".repeat(50), " 이름 ! \n");
	}

	@ParameterizedTest
	@MethodSource("invalidNicknames")
	void rejectsInvalidNicknames(String nickname) throws Exception {
		fieldError(body(EMAIL, PASSWORD, nickname), "nickname");
	}

	static Stream<String> invalidNicknames() {
		return Stream.of("", " \t\n", "\u00a0\u3000", "a".repeat(51), "😀".repeat(51), "name\u0000");
	}

	@ParameterizedTest
	@ValueSource(strings = {"email", "password", "nickname"})
	void missingNullAndNonStringFieldsAreRejected(String field) throws Exception {
		var input = new LinkedHashMap<String, Object>(Map.of("email", EMAIL, "password", PASSWORD, "nickname", NICKNAME));
		input.remove(field);
		fieldError(mapper.writeValueAsString(input), field);
		input.put(field, null);
		fieldError(mapper.writeValueAsString(input), field);
		for (Object value : new Object[] {123, true, java.util.List.of(), Map.of()}) {
			input.put(field, value);
			error(mapper.writeValueAsString(input), 400, "INVALID_REQUEST");
		}
		verifyNoInteractions(members, refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \t ", "not-an-email", "user @example.com", "user@example .com"})
	void rejectsInvalidEmail(String email) throws Exception {
		fieldError(body(email, PASSWORD, NICKNAME), "email");
	}

	@Test
	void emailLengthIsCheckedAfterNormalization() throws Exception {
		String email = emailWithLength(255);
		prepareSuccess(email);
		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content(body(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD, NICKNAME)))
			.andExpect(status().isOk());
		assertThat(error(body(emailWithLength(256), PASSWORD, NICKNAME), 400, "VALIDATION_FAILED")
			.get("errors").findValuesAsText("field")).contains("email");
	}

	@Test
	void preservesEmailDotsAndPlusAliases() throws Exception {
		String email = "first.last+tag@gmail.com";
		prepareSuccess(email);
		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content(body(" FIRST.LAST+TAG@GMAIL.COM ", PASSWORD, NICKNAME))).andExpect(status().isOk());
		verify(members).existsByEmail(email);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]"})
	void malformedBodyIsInvalidRequest(String body) throws Exception {
		error(body, 400, "INVALID_REQUEST");
		verifyNoInteractions(members, refresh);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void duplicateAtLookupOrInsertReturnsConflictWithoutTokens(boolean foundAtLookup) throws Exception {
		when(members.existsByEmail(EMAIL)).thenReturn(foundAtLookup);
		JsonNode result = error(body(EMAIL, PASSWORD, NICKNAME), 409, "EMAIL_ALREADY_REGISTERED");
		assertThat(result.get("message").asText()).isEqualTo("이미 가입된 이메일입니다.");
		verifyNoInteractions(refresh);
		verify(tokens, never()).issueAccessToken(anyLong());
		verify(tokens, never()).issueRefreshToken(anyLong());
		if (foundAtLookup) {
			verify(members, never()).insertLocalMemberIfAbsent(anyString(), anyString(), anyString());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"LOOKUP", "INSERT", "ISSUE", "STORE"})
	void infrastructureFailuresAreSafeServerErrors(String stage) throws Exception {
		prepareSuccess(EMAIL);
		var failure = new DataAccessResourceFailureException("private-infrastructure-detail");
		switch (stage) {
			case "LOOKUP" -> when(members.existsByEmail(EMAIL)).thenThrow(failure);
			case "INSERT" -> when(members.insertLocalMemberIfAbsent(anyString(), anyString(), anyString())).thenThrow(failure);
			case "ISSUE" -> doThrow(failure).when(tokens).issueRefreshToken(MEMBER_ID);
			case "STORE" -> doThrow(failure).when(refresh).store(anyLong(), anyString());
			default -> throw new AssertionError(stage);
		}
		assertThat(error(body(EMAIL, PASSWORD, NICKNAME), 500, "INTERNAL_SERVER_ERROR")
			.get("message").asText()).isEqualTo("서버 내부 오류가 발생했습니다.");
	}

	@Test
	void requestToStringDoesNotExposeCredentials() {
		assertThat(new SignupRequest(EMAIL, PASSWORD, NICKNAME).toString()).doesNotContain(EMAIL, PASSWORD, NICKNAME);
	}

	private void prepareSuccess(String email) {
		Member member = Member.builder().email(email).build();
		ReflectionTestUtils.setField(member, "memberId", MEMBER_ID);
		when(members.insertLocalMemberIfAbsent(eq(email), anyString(), anyString())).thenReturn(1);
		when(members.findByEmail(email)).thenReturn(Optional.of(member));
	}

	private void fieldError(String body, String field) throws Exception {
		assertThat(error(body, 400, "VALIDATION_FAILED").get("errors").findValuesAsText("field")).contains(field);
		verifyNoInteractions(members, refresh);
	}

	private JsonNode error(String body, int status, String code) throws Exception {
		var response = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(status)).andReturn().getResponse();
		JsonNode result = mapper.readTree(response.getContentAsByteArray());
		assertThat(result.get("code").asText()).isEqualTo(code);
		assertThat(result.get("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(result.has("data")).isFalse();
		assertThat(result.has("success")).isFalse();
		assertThat(result.toString()).doesNotContain(PASSWORD, EMAIL, "private-infrastructure-detail",
			"rejectedValue", "Exception", "accessToken", "refreshToken");
		if (code.equals("VALIDATION_FAILED")) {
			assertThat(result.size()).isEqualTo(4);
			result.get("errors").forEach(field -> assertThat(field.size()).isEqualTo(2));
		} else {
			assertThat(result.size()).isEqualTo(3);
			assertThat(result.has("errors")).isFalse();
		}
		return result;
	}

	private String body(String email, String password, String nickname) throws Exception {
		return mapper.writeValueAsString(Map.of("email", email, "password", password, "nickname", nickname));
	}

	private String emailWithLength(int length) {
		return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(length - 193);
	}
}
