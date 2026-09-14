package com.ssafy.thispatch.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.ssafy.thispatch.global.security.SecurityErrorHandler;

import jakarta.servlet.DispatcherType;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errorHandler) throws Exception {
		return http
			// 인증은 Authorization 헤더로 전달한다. 브라우저 자동 전송 인증은 사용하지 않는다.
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			// CorsConfig의 서블릿 필터가 먼저 처리하므로 체인 안에 중복 등록하지 않는다.
			.cors(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				// 이미 발생한 오류의 내부 dispatch가 인증 오류로 바뀌지 않도록 한다.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.GET, "/session", "/auth/steam/login", "/auth/steam/callback").permitAll()
				.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/signup", "/auth/refresh",
					"/auth/steam/token", "/auth/steam/signup").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.build();
	}
}
