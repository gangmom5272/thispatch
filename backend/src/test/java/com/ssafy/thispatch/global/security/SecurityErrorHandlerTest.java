package com.ssafy.thispatch.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

import com.fasterxml.jackson.databind.ObjectMapper;

class SecurityErrorHandlerTest {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void securityExceptionsUseCommonJsonAndKoreanTimeWithoutInternalDetails(boolean authenticated) throws Exception {
		var mapper = new ObjectMapper();
		var handler = new SecurityErrorHandler(mapper);
		var filter = new ExceptionTranslationFilter(handler, new NullRequestCache());
		filter.setAccessDeniedHandler(handler);
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authenticated
			? UsernamePasswordAuthenticationToken.authenticated("member", null, AuthorityUtils.NO_AUTHORITIES)
			: new AnonymousAuthenticationToken("test-key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
		SecurityContextHolder.setContext(context);
		var request = new MockHttpServletRequest("GET", "/protected");
		var response = new MockHttpServletResponse();
		var before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		filter.doFilter(request, response, (req, res) -> {
			throw new AccessDeniedException("private-exception-details token=must-not-leak");
		});

		assertThat(response.getStatus()).isEqualTo(authenticated ? 403 : 401);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(authenticated ? null : "Bearer");
		var body = mapper.readTree(response.getContentAsString());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("code").asText()).isEqualTo(authenticated ? "FORBIDDEN" : "UNAUTHORIZED");
		assertThat(body.path("message").asText()).isEqualTo(authenticated ? "접근 권한이 없습니다." : "인증이 필요합니다.");
		assertThat(response.getContentAsString()).doesNotContain("private-exception-details", "must-not-leak", "AccessDeniedException");
		var time = LocalDateTime.parse(body.path("responsedAt").asText(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
			.atZone(ZoneId.of("Asia/Seoul")).toInstant();
		assertThat(time).isBetween(before, Instant.now());
		assertThat(request.getSession(false)).isNull();
	}
}
