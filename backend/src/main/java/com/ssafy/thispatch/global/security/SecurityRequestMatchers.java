package com.ssafy.thispatch.global.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** 접근 허용과 JWT 필터 제외에 동일한 Method·경로 규칙을 사용한다. */
public final class SecurityRequestMatchers {

	public static final RequestMatcher SESSION = matcher(HttpMethod.GET, "/session");
	public static final RequestMatcher PUBLIC_AUTH = new OrRequestMatcher(
		matcher(HttpMethod.GET, "/auth/steam/login"),
		matcher(HttpMethod.GET, "/auth/steam/callback"),
		matcher(HttpMethod.POST, "/auth/login"),
		matcher(HttpMethod.POST, "/auth/signup"),
		matcher(HttpMethod.POST, "/auth/refresh"),
		matcher(HttpMethod.POST, "/auth/steam/token"),
		matcher(HttpMethod.POST, "/auth/steam/signup"));

	private SecurityRequestMatchers() {
	}

	private static RequestMatcher matcher(HttpMethod method, String path) {
		return PathPatternRequestMatcher.withDefaults().matcher(method, path);
	}
}
