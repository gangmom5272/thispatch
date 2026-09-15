package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.domain.member.service.TokenRefreshService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(TokenRefreshController.class)
@Import({TokenRefreshService.class, RefreshTokenService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class TokenRefreshControllerTest extends com.ssafy.thispatch.support.ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JwtProperties properties;
	@MockitoBean
	private MemberRepository members;

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void refreshReturnsOnlyAccessTokenForBothLoginTypesWithoutNickname(LoginType type) throws Exception {
		String refreshToken = tokens.issueRefreshToken(MEMBER_ID);
		Member member = storedMember(type, "ACTIVE", refreshToken);
		when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer ignored-invalid-token")
				.content(body(refreshToken)))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("토큰 재발급에 성공했습니다."))
			.andExpect(jsonPath("$.success").value(true)).andReturn();
		JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(response.size()).isEqualTo(5);
		assertThat(response.get("data").size()).isEqualTo(1);
		String access = response.get("data").get("accessToken").asText();
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(MEMBER_ID);
		assertThat(result.getRequest().getSession(false)).isNull();
		Instant responseTime = LocalDateTime.parse(response.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(responseTime).isBetween(before, Instant.now());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}",
		"{\"refreshToken\":\"  \"}"})
	void missingOrBlankTokenIsFieldValidationFailure(String body) throws Exception {
		JsonNode response = error(body, 400, "VALIDATION_FAILED", "입력값을 확인해주세요.");
		assertThat(response.size()).isEqualTo(4);
		assertThat(response.get("errors").size()).isEqualTo(1);
		assertThat(response.get("errors").get(0).size()).isEqualTo(2);
		assertThat(response.get("errors").get(0).get("field").asText()).isEqualTo("refreshToken");
		assertThat(response.get("errors").get(0).get("message").asText()).isEqualTo("Refresh Token을 입력해주세요.");
		verifyNoInteractions(members);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "[]", "{\"refreshToken\":123}", "{\"refreshToken\":true}",
		"{\"refreshToken\":[]}", "{\"refreshToken\":{}}"})
	void malformedBodyAndNonStringTokenAreInvalidRequests(String body) throws Exception {
		assertThat(error(body, 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.").size()).isEqualTo(3);
		verifyNoInteractions(members);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MALFORMED", "ACCESS", "EXPIRED", "FORGED"})
	void invalidJwtIsRejectedBeforeMemberLookup(String kind) throws Exception {
		String token = switch (kind) {
			case "ACCESS" -> tokens.issueAccessToken(MEMBER_ID);
			case "EXPIRED" -> new JwtTokenProvider(properties,
				Clock.fixed(Instant.now().minus(properties.refreshTokenTtl()).minusSeconds(1), ZoneOffset.UTC))
				.issueRefreshToken(MEMBER_ID);
			case "FORGED" -> new JwtTokenProvider(new JwtProperties(
				"YW5vdGhlci10ZXN0LW9ubHkta2V5LXdoaWNoLWlzLWxvbmctZW5vdWdo", "HS256",
				Duration.ofMinutes(15), Duration.ofDays(7)), Clock.systemUTC()).issueRefreshToken(MEMBER_ID);
			default -> "private-malformed-token";
		};
		assertTokenError(token);
		verifyNoInteractions(members);
	}

	@Test
	void missingMemberHasDistinctError() throws Exception {
		String token = tokens.issueRefreshToken(MEMBER_ID);
		when(members.findById(MEMBER_ID)).thenReturn(Optional.empty());
		assertMemberError(token);
	}

	@ParameterizedTest
	@ValueSource(strings = {"WITHDRAWN", "UNKNOWN"})
	void inactiveMemberWithMatchingStorageHasDistinctError(String state) throws Exception {
		String token = tokens.issueRefreshToken(MEMBER_ID);
		when(members.findById(MEMBER_ID)).thenReturn(Optional.of(storedMember(LoginType.LOCAL, state, token)));
		assertMemberError(token);
	}

	@ParameterizedTest
	@ValueSource(strings = {"UNSTORED", "REPLACED", "EXPIRY", "INACTIVE_UNSTORED"})
	void invalidStoragePrecedesInactiveMemberError(String kind) throws Exception {
		String token = tokens.issueRefreshToken(MEMBER_ID);
		Member member = storedMember(LoginType.LOCAL, kind.startsWith("INACTIVE") ? "WITHDRAWN" : "ACTIVE", token);
		if (kind.equals("EXPIRY")) {
			ReflectionTestUtils.setField(member, "refreshTokenExpiresAt", Instant.EPOCH);
		} else {
			ReflectionTestUtils.setField(member, "refreshTokenHash", kind.equals("REPLACED") ? "0".repeat(64) : null);
		}
		when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
		assertTokenError(token);
	}

	@Test
	void databaseFailureRemainsSafeServerError() throws Exception {
		String token = tokens.issueRefreshToken(MEMBER_ID);
		when(members.findById(MEMBER_ID)).thenThrow(
			new DataAccessResourceFailureException("private-database-detail"));
		assertThat(error(body(token), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.").size())
			.isEqualTo(3);
	}

	@Test
	void queryAndCookieCannotReplaceRequestBody() throws Exception {
		mvc.perform(post("/auth/refresh").param("refreshToken", "private-query-token")
				.cookie(new jakarta.servlet.http.Cookie("refreshToken", "private-cookie-token")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(members);
	}

	private void assertTokenError(String token) throws Exception {
		assertThat(error(body(token), 401, "REFRESH_TOKEN_INVALID", "토큰 재발급을 위해 다시 로그인해주세요.").size())
			.isEqualTo(3);
	}

	private void assertMemberError(String token) throws Exception {
		assertThat(error(body(token), 401, "MEMBER_INACTIVE", "토큰을 재발급할 수 없는 회원입니다.").size())
			.isEqualTo(3);
	}

	private JsonNode error(String body, int statusCode, String code, String message) throws Exception {
		var result = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(statusCode))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.responsedAt").isString()).andReturn();
		if (statusCode == 401) {
			assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
		JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(response.has("data")).isFalse();
		assertThat(response.has("success")).isFalse();
		assertThat(response.toString()).doesNotContain("private-", "rejectedValue", "Exception", "eyJ");
		return response;
	}

	private String body(String token) throws Exception {
		return mapper.writeValueAsString(java.util.Map.of("refreshToken", token));
	}

	private Member storedMember(LoginType type, String status, String token) throws Exception {
		Member member = Member.builder().loginType(type).status(status).createdAt(Instant.now()).build();
		ReflectionTestUtils.setField(member, "memberId", MEMBER_ID);
		ReflectionTestUtils.setField(member, "refreshTokenHash", HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))));
		ReflectionTestUtils.setField(member, "refreshTokenExpiresAt", tokens.validateRefreshToken(token).expiresAt());
		return member;
	}
}
