package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.global.security.SecurityErrorHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

@WebMvcTest(SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

	@Autowired
	private MockMvc mvc;

	@ParameterizedTest
	@CsvSource({
		"GET, /session", "GET, /auth/steam/login", "GET, /auth/steam/callback",
		"POST, /auth/login", "POST, /auth/signup", "POST, /auth/refresh",
		"POST, /auth/steam/token", "POST, /auth/steam/signup"
	})
	void publicEndpointsPassWithoutAuthenticationOrCsrfToken(String method, String path) throws Exception {
		var result = mvc.perform(request(HttpMethod.valueOf(method), path))
			.andExpect(status().isNoContent()).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@ParameterizedTest
	@CsvSource({
		"POST, /auth/logout", "DELETE, /members/me", "GET, /genres", "GET, /games", "GET, /games/1",
		"POST, /games/1/my-game", "GET, /games/1/reviews", "GET, /games/1/reviews/representative",
		"GET, /games/1/reaction-trends", "GET, /games/1/summaries/reaction-trends",
		"GET, /games/1/playtime-topics", "GET, /games/1/summaries/playtime-topics",
		"GET, /games/1/language-analysis", "GET, /games/1/language-analysis/ko",
		"POST, /games/1/plan-structures", "POST, /games/1/case-searches", "GET, /games/1/patches/1"
	})
	void protectedEndpointsRequireAuthentication(String method, String path) throws Exception {
		var rejected = mvc.perform(request(HttpMethod.valueOf(method), path))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.message").value("인증이 필요합니다."))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(rejected.getRequest().getSession(false)).isNull();

		mvc.perform(request(HttpMethod.valueOf(method), path).with(user("member")))
			.andExpect(status().isNoContent());
	}

	@ParameterizedTest
	@CsvSource({
		"POST, /session", "GET, /auth/login", "GET, /auth/signup", "GET, /auth/refresh",
		"POST, /auth/steam/login", "POST, /auth/steam/callback", "GET, /auth/steam/token",
		"GET, /auth/steam/signup", "GET, /auth/unknown", "GET, /login", "POST, /logout", "GET, /error"
	})
	void publicRulesDoNotOpenOtherMethodsOrAuthPaths(String method, String path) throws Exception {
		mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized());
	}

	@Test
	void sessionAllowsAuthenticatedUserAsWell() throws Exception {
		mvc.perform(get("/session").with(user("member"))).andExpect(status().isNoContent());
	}

	@Test
	void doesNotAuthenticateUsingBasicCredentialsOrUnverifiedBearerToken() throws Exception {
		mvc.perform(get("/games").with(httpBasic("user", "password"))).andExpect(status().isUnauthorized());
		mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, "Bearer unverified-token"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void doesNotReuseAuthenticationStoredInHttpSession() throws Exception {
		var session = new MockHttpSession();
		var authentication = UsernamePasswordAuthenticationToken.authenticated("member", null,
			AuthorityUtils.createAuthorityList("ROLE_USER"));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
			new SecurityContextImpl(authentication));
		mvc.perform(get("/games").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void internalErrorDispatchIsNotReplacedWithAuthenticationFailure() throws Exception {
		mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404).with(request -> {
			request.setDispatcherType(DispatcherType.ERROR);
			return request;
		})).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
	}

	// 실제 도메인 API를 구현하지 않고 Security 통과 여부만 확인하는 테스트 전용 수신점.
	@RestController
	static class ProbeController {
		@RequestMapping("/**")
		@ResponseStatus(HttpStatus.NO_CONTENT)
		void handle() {
		}
	}
}
