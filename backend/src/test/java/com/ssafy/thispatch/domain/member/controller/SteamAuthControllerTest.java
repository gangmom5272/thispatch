package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@Import({SteamLoginService.class, AppConfig.class, SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class})
@ActiveProfiles("test")
class SteamAuthControllerTest {

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
}