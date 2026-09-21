package com.ssafy.thispatch.global.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CorsConfig {

	@Bean
	public UrlBasedCorsConfigurationSource corsConfigurationSource(AppProperties properties) {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.cors().allowedOrigins().stream().map(URI::toString).toList());
		cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		cors.setAllowCredentials(false);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilterRegistration(UrlBasedCorsConfigurationSource source) {
		var registration = new FilterRegistrationBean<>(new CorsFilter(source));
		// 인증 정보가 없는 preflight도 Security 필터보다 먼저 처리한다.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
