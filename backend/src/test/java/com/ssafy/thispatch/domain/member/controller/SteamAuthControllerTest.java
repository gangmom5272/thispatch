package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssafy.thispatch.client.steam.SteamOpenIdClient;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.service.SteamCallbackService;
import com.ssafy.thispatch.domain.member.service.SteamMemberService;
import com.ssafy.thispatch.domain.member.service.SteamLoginCodeService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.thispatch.domain.member.service.SteamLoginService;
import com.ssafy.thispatch.domain.member.service.SteamTokenService;
import com.ssafy.thispatch.global.config.AppConfig;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;

@WebMvcTest(SteamAuthController.class)
@Import({SteamCallbackService.class, SteamLoginService.class, AppConfig.class, SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class})
@ActiveProfiles("test")
class SteamAuthControllerTest {
	@MockitoBean
	private SteamOpenIdClient openIdClient;

	@MockitoBean
	private SteamMemberService members;

	@MockitoBean
	private SteamLoginCodeService codes;

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private SteamTokenService steamTokenService;

	@Test
	void redirectsAnonymousBrowserWithoutJsonOrSession() throws Exception {
		var result = mvc.perform(get("/auth/steam/login"))
			.andExpect(status().isFound())
			.andExpect(header().exists(HttpHeaders.LOCATION))
			.andExpect(content().string(""))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
			.andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
			.startsWith("https://steamcommunity.com/openid/login?")
			.contains("openid.mode=checkid_setup")
			.contains("openid.return_to=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fsteam%2Fcallback")
			.contains("openid.realm=http%3A%2F%2Flocalhost%3A8080");
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	void requestHeadersAndParametersCannotOverrideConfiguredRedirect() throws Exception {
		String expected = mvc.perform(get("/auth/steam/login")).andReturn()
			.getResponse().getHeader(HttpHeaders.LOCATION);

		mvc.perform(get("/auth/steam/login")
				.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
				.header(HttpHeaders.HOST, "attacker.example")
				.header("Forwarded", "host=attacker.example;proto=https")
				.header("X-Forwarded-Host", "attacker.example")
				.header("X-Forwarded-Proto", "https")
				.param("return_to", "https://attacker.example")
				.param("openid.realm", "https://attacker.example"))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION, expected))
			.andExpect(content().string(""));
	}

	@Test
	void callbackRedirectsAnonymousUserWithOnlyLoginCode() throws Exception {
		var steamId = new BigInteger("76561198000000001");
		var member = Member.builder().loginType(LoginType.STEAM).status("ACTIVE")
			.steamId(steamId).createdAt(Instant.now()).build();
		ReflectionTestUtils.setField(member, "memberId", 42L);
		when(openIdClient.verify(any())).thenReturn(Optional.of(steamId));
		when(members.findOrCreate(steamId)).thenReturn(member);
		when(codes.issue(42)).thenReturn("temporary-code");
		var result = mvc.perform(get("/auth/steam/callback")
				.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
				.param("openid.mode", "id_res").param("redirect", "https://attacker.example"))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				"http://localhost:5173/auth/steam/callback?loginCode=temporary-code"))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
			.andExpect(content().string("")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	void missingAndCancelledAuthenticationRedirectWithoutJson() throws Exception {
		when(openIdClient.verify(any())).thenReturn(Optional.empty());
		for (String mode : new String[] {"", "cancel"}) {
			mvc.perform(get("/auth/steam/callback").param("openid.mode", mode))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION,
					"http://localhost:5173/login?error=STEAM_AUTH_FAILED"))
				.andExpect(content().string(""));
		}
		verifyNoInteractions(members, codes);
	}

	@Test
	void internalFailureUsesCommonSafeJsonAndDoesNotRedirect() throws Exception {
		var steamId = new BigInteger("76561198000000001");
		when(openIdClient.verify(any())).thenReturn(Optional.of(steamId));
		when(members.findOrCreate(steamId)).thenThrow(new IllegalStateException("private database detail"));
		var result = mvc.perform(get("/auth/steam/callback"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private database detail");
		verifyNoInteractions(codes);
	}
}
