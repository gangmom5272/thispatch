package com.ssafy.thispatch.global.security.jwt;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ssafy.thispatch.domain.member.service.MemberAccessService;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.SecurityRequestMatchers;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Security 체인에만 등록한다. 보호 API는 검증된 JWT와 현재 ACTIVE 회원 상태를 모두 요구한다. */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Pattern BEARER = Pattern.compile("Bearer +([A-Za-z0-9._~+/-]+=*)", Pattern.CASE_INSENSITIVE);

	private final JwtTokenProvider tokenProvider;
	private final SecurityErrorHandler errorHandler;
	private final MemberAccessService memberAccessService;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getDispatcherType() == DispatcherType.ERROR || SecurityRequestMatchers.PUBLIC_AUTH.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		try {
			String token = bearerToken(request);
			if (token != null) {
				VerifiedToken verified = tokenProvider.validateAccessToken(token);
				if (!SecurityRequestMatchers.SESSION.matches(request)) {
					boolean active;
					try {
						active = memberAccessService.isActive(verified.memberId());
					} catch (RuntimeException exception) {
						SecurityContextHolder.clearContext();
						errorHandler.serverError(response, exception);
						return;
					}
					if (!active) {
						throw new TokenValidationException(Reason.INVALID);
					}
				}
				var authentication = UsernamePasswordAuthenticationToken.authenticated(
					new MemberPrincipal(verified.memberId()), null, List.of());
				var context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(authentication);
				SecurityContextHolder.setContext(context);
			}
		} catch (TokenValidationException exception) {
			SecurityContextHolder.clearContext();
			if (exception.getReason() != Reason.EXPIRED || !SecurityRequestMatchers.SESSION.matches(request)) {
				errorHandler.commence(request, response, new BadCredentialsException("Invalid access token"));
				return;
			}
		}
		// 이후 필터·컨트롤러의 예외를 JWT 인증 실패로 바꾸지 않는다.
		// 요청 종료 시 SecurityContextHolderFilter가 context를 정리한다.
		filterChain.doFilter(request, response);
	}

	private String bearerToken(HttpServletRequest request) {
		var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
		if (!headers.hasMoreElements()) {
			return null;
		}
		String header = headers.nextElement();
		var matcher = BEARER.matcher(header);
		if (headers.hasMoreElements() || !matcher.matches()) {
			throw new TokenValidationException(Reason.INVALID);
		}
		return matcher.group(1);
	}
}
