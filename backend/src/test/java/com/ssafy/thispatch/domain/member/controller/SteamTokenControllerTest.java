package com.ssafy.thispatch.domain.member.controller;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.domain.member.service.SteamCallbackService;
import com.ssafy.thispatch.domain.member.service.SteamLoginCodeService;
import com.ssafy.thispatch.domain.member.service.SteamLoginService;
import com.ssafy.thispatch.domain.member.service.SteamTokenService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@WebMvcTest(SteamAuthController.class)
@Import({SteamTokenService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class SteamTokenControllerTest extends com.ssafy.thispatch.support.ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final String CODE = "A".repeat(43);

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private JwtTokenProvider tokens;
	@MockitoBean
	private SteamLoginService login;
	@MockitoBean
	private SteamCallbackService steamCallbackService;
	@MockitoBean
	private SteamLoginCodeService codes;
	@MockitoBean
	private MemberRepository members;
	@MockitoBean
	private RefreshTokenService refresh;

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"기존 회원"})
	void anonymousExchangeReturnsTokensAndAlwaysIncludesNickname(String nickname) throws Exception {
		when(codes.consume(CODE)).thenReturn(MEMBER_ID);
		when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", nickname)));
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var result = mvc.perform(post("/auth/steam/token").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer ignored-invalid-token")
				.content(body(CODE)))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
			.andExpect(jsonPath("$.success").value(true)).andReturn();
		JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(response.size()).isEqualTo(5);
		JsonNode data = response.get("data");
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.has("nickname")).isTrue();
		assertThat(data.get("nickname")).isEqualTo(mapper.valueToTree(nickname));
		assertThat(data.has("onboardingRequired")).isFalse();
		String accessToken = data.get("accessToken").asText();
		String refreshToken = data.get("refreshToken").asText();
		assertThat(tokens.validateAccessToken(accessToken).memberId()).isEqualTo(MEMBER_ID);
		assertThat(tokens.validateRefreshToken(refreshToken).memberId()).isEqualTo(MEMBER_ID);
		verify(refresh).store(MEMBER_ID, refreshToken);
		verify(codes).consume(CODE);
		assertThat(result.getRequest().getSession(false)).isNull();
		Instant responseTime = LocalDateTime.parse(response.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(responseTime).isBetween(before, Instant.now());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"loginCode\":null}", "{\"loginCode\":\"\"}", "{\"loginCode\":\"  \"}"})
	void missingOrBlankCodeIsFieldValidationFailure(String body) throws Exception {
		JsonNode error = error(body, 400, "VALIDATION_FAILED", "입력값을 확인해주세요.");
		assertThat(error.size()).isEqualTo(4);
		assertThat(error.get("errors").get(0).get("field").asText()).isEqualTo("loginCode");
		assertThat(error.get("errors").get(0).size()).isEqualTo(2);
		verifyNoInteractions(codes, members, refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "{\"loginCode\":123}", "{\"loginCode\":true}",
		"{\"loginCode\":[]}", "{\"loginCode\":{}}", "[]"})
	void malformedBodyAndNonStringCodeAreInvalidRequests(String body) throws Exception {
		assertThat(error(body, 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.").size()).isEqualTo(3);
		verifyNoInteractions(codes, members, refresh);
	}

	@Test
	void invalidExpiredOrConsumedCodeReturnsSameUnauthorizedError() throws Exception {
		when(codes.consume(CODE)).thenThrow(new BusinessException(STEAM_LOGIN_CODE_INVALID));
		assertThat(error(body(CODE), 401, "STEAM_LOGIN_CODE_INVALID", "Steam 로그인을 다시 진행해주세요.").size())
			.isEqualTo(3);
		verifyNoInteractions(members, refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WITHDRAWN", "UNKNOWN", "LOCAL"})
	void ineligibleMembersCannotReceiveTokens(String state) throws Exception {
		when(codes.consume(CODE)).thenReturn(MEMBER_ID);
		Optional<Member> member = state.equals("MISSING") ? Optional.empty()
			: Optional.of(member(state.equals("LOCAL") ? LoginType.LOCAL : LoginType.STEAM,
				state.equals("LOCAL") ? "ACTIVE" : state, null));
		when(members.findById(MEMBER_ID)).thenReturn(member);
		assertThat(error(body(CODE), 401, "STEAM_LOGIN_CODE_INVALID", "Steam 로그인을 다시 진행해주세요.").size())
			.isEqualTo(3);
		verifyNoInteractions(refresh);
	}

	@ParameterizedTest
	@ValueSource(strings = {"REDIS", "LOOKUP", "STORE"})
	void infrastructureFailureReturnsSafeServerError(String stage) throws Exception {
		var failure = new DataAccessResourceFailureException("private-infrastructure-detail");
		if (stage.equals("REDIS")) {
			when(codes.consume(CODE)).thenThrow(failure);
		} else {
			when(codes.consume(CODE)).thenReturn(MEMBER_ID);
			if (stage.equals("LOOKUP")) {
				when(members.findById(MEMBER_ID)).thenThrow(failure);
			} else {
				when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(LoginType.STEAM, "ACTIVE", null)));
				doThrow(failure).when(refresh).store(anyLong(), anyString());
			}
		}
		assertThat(error(body(CODE), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.").size())
			.isEqualTo(3);
	}

	private JsonNode error(String body, int status, String code, String message) throws Exception {
		var result = mvc.perform(post("/auth/steam/token").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andReturn();
		JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(response.has("data")).isFalse();
		assertThat(response.has("success")).isFalse();
		assertThat(response.toString()).doesNotContain(CODE, "private-infrastructure-detail", "rejectedValue",
			"Exception", "accessToken", "refreshToken");
		return response;
	}

	private String body(String code) {
		return "{\"loginCode\":\"" + code + "\"}";
	}

	private Member member(LoginType type, String status, String nickname) {
		return Member.builder().loginType(type).status(status).nickname(nickname).createdAt(Instant.now()).build();
	}
}
