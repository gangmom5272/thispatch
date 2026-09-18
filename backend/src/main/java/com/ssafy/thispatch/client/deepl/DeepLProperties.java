package com.ssafy.thispatch.client.deepl;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.deepl")
public record DeepLProperties(String apiKey, Duration connectTimeout, Duration readTimeout) {

	public DeepLProperties {
		apiKey = apiKey == null ? "" : apiKey.strip();
		connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
		readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
		if (connectTimeout.toMillis() < 1 || readTimeout.toMillis() < 1) {
			throw new IllegalArgumentException("DeepL timeouts must be at least one millisecond");
		}
	}

	public String baseUrl() {
		return apiKey.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
	}

	@Override
	public String toString() {
		return "DeepLProperties[apiKey=<redacted>, connectTimeout=" + connectTimeout
			+ ", readTimeout=" + readTimeout + "]";
	}
}
