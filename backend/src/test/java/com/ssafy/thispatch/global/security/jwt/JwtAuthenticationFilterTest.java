package com.ssafy.thispatch.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;

@WebMvcTest(JwtAuthenticationFilterTest.ProbeController.class)
@Import({SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class,
	JwtAuthenticationFilterTest.ProbeController.class})
@ActiveProfiles("test")
class JwtAuthenticationFilterTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JwtProperties properties;

	@AfterEach
	void contextIsClearedAfterRequest() {
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(strings = {"Bearer ", "bearer ", "bEaReR   "})
	void verifiedAccessTokenSuppliesPrincipalWithoutCredentialsOrInventedRoles(String prefix) throws Exception {
		var result = mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, prefix + tokens.issueAccessToken(42)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.memberId").value(42))
			.andExpect(jsonPath("$.name").value("42"))
			.andExpect(jsonPath("$.authenticated").value(true))
			.andExpect(jsonPath("$.credentialsAbsent").value(true))
			.andExpect(jsonPath("$.authorities").isEmpty()).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		mvc.perform(get("/games")).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/session", "/games"})
	void refreshAndExpiredRefreshCannotAuthenticate(String path) throws Exception {
		for (String token : List.of(tokens.issueRefreshToken(42),
			new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8))).issueRefreshToken(42))) {
			assertUnauthorized(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
		}
	}

	@ParameterizedTest
	@MethodSource("malformedHeaders")
	void malformedCredentialsAreRejectedOnProtectedAndOptionalPaths(String authorization) throws Exception {
		for (String path : List.of("/games", "/session")) {
			assertUnauthorized(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, authorization)));
		}
	}

	static Stream<String> malformedHeaders() {
		return Stream.of("", "Bearer", "Bearer ", "Basic abc", "Bearer\tabc", "Bearer invalid-token",
			"Bearer a.b.c", "Bearer abc, Bearer def", "Bearer abc def", "Bearer abc ");
	}

	@Test
	void rejectsDuplicateAuthorizationEvenWhenBothTokensAreValid() throws Exception {
		String token = "Bearer " + tokens.issueAccessToken(42);
		for (String path : List.of("/session", "/games")) {
			assertUnauthorized(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token, token)));
		}
	}

	@Test
	void rejectsForgedSignatureOnOptionalAndProtectedPaths() throws Exception {
		String token = tokens.issueAccessToken(42);
		int signatureStart = token.lastIndexOf('.') + 1;
		String forged = token.substring(0, signatureStart)
			+ (token.charAt(signatureStart) == 'A' ? 'B' : 'A') + token.substring(signatureStart + 1);
		for (String path : List.of("/session", "/games")) {
			assertUnauthorized(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)));
		}
	}

	@Test
	void sessionPassesAnonymouslyOnlyForMissingOrExpiredAccessToken() throws Exception {
		mvc.perform(get("/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
		String expired = expiredAccessToken();
		mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
			.andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
		assertUnauthorized(mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)));
		mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(42)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(42));
	}

	@ParameterizedTest
	@CsvSource({"GET, /auth/steam/login", "GET, /auth/steam/callback", "POST, /auth/login",
		"POST, /auth/signup", "POST, /auth/refresh", "POST, /auth/steam/token"})
	void publicAuthEndpointsIgnoreAccessCredentials(String method, String path) throws Exception {
		for (String token : List.of("invalid-token", expiredAccessToken(), tokens.issueAccessToken(42),
			tokens.issueRefreshToken(42))) {
			mvc.perform(request(HttpMethod.valueOf(method), path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
		}
	}

	@ParameterizedTest
	@CsvSource({"POST, /session", "GET, /auth/refresh", "POST, /auth/steam/callback",
		"GET, /auth/steam/login/extra", "GET, /auth/steam/login/"})
	void similarPathsDoNotInheritFilterExceptions(String method, String path) throws Exception {
		assertUnauthorized(mvc.perform(request(HttpMethod.valueOf(method), path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken())));
	}

	@Test
	void pathRulesWorkUnderContextPath() throws Exception {
		mvc.perform(get("/api/session").contextPath("/api")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
		mvc.perform(get("/api/games").contextPath("/api")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(42)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(42));
	}

	@Test
	void doesNotUseTokenFromQueryOrCookie() throws Exception {
		String token = tokens.issueAccessToken(42);
		assertUnauthorized(mvc.perform(get("/games").param("access_token", token)
			.cookie(new Cookie("accessToken", token))));
	}

	@Test
	void internalErrorDispatchIgnoresInvalidBearer() throws Exception {
		mvc.perform(get("/error").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404).with(request -> {
				request.setDispatcherType(DispatcherType.ERROR);
				return request;
			})).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void downstreamExceptionIsNotChangedToUnauthorized() throws Exception {
		mvc.perform(get("/probe/failure").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(42)))
			.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
	}

	private String expiredAccessToken() {
		return new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofHours(-1))).issueAccessToken(42);
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		var response = result.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.message").value("인증이 필요합니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$").value(org.hamcrest.Matchers.aMapWithSize(3))).andReturn();
		assertThat(response.getRequest().getSession(false)).isNull();
	}

	// 실제 회원 API 대신 SecurityContext와 @AuthenticationPrincipal 전달만 확인한다.
	@RestController
	static class ProbeController {
		@RequestMapping("/**")
		Probe handle(@AuthenticationPrincipal MemberPrincipal principal, Authentication authentication) {
			return new Probe(principal != null, principal == null ? null : principal.memberId(),
				principal == null ? null : principal.getName(), authentication == null || authentication.getCredentials() == null,
				authentication == null ? List.of() : authentication.getAuthorities().stream().map(Object::toString).toList());
		}

		@GetMapping("/probe/failure")
		void fail() {
			throw new TokenValidationException(TokenValidationException.Reason.INVALID);
		}
	}

	record Probe(boolean authenticated, Long memberId, String name, boolean credentialsAbsent, List<String> authorities) {
	}
}
