package com.ssafy.thispatch.client.ai;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

	public AiProperties {
		baseUrl = baseUrl == null ? "" : baseUrl.strip();
		connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
		// 화면 요청이 AI 내부 재시도(최대 수 분)를 끝까지 기다리지 않도록 제한한다.
		readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
		if (connectTimeout.isZero() || connectTimeout.isNegative()
			|| readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("AI timeouts must be positive");
		}
		if (!baseUrl.isEmpty()) {
			URI uri = URI.create(baseUrl);
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
				|| uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getQuery() != null || uri.getFragment() != null) {
				throw new IllegalArgumentException("AI base URL must be an HTTP(S) server address");
			}
		}
	}
}
