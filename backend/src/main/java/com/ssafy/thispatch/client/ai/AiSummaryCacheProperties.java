package com.ssafy.thispatch.client.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai.summary-cache")
public record AiSummaryCacheProperties(@DefaultValue("true") boolean enabled,
	@DefaultValue("30m") Duration ttl, @DefaultValue("v1") String version,
	@DefaultValue("40s") Duration waitTimeout) {

	public AiSummaryCacheProperties {
		if (ttl == null || ttl.toMillis() < 1 || waitTimeout == null || waitTimeout.toMillis() < 1
			|| version == null || !version.matches("[A-Za-z0-9_-]{1,64}")) {
			throw new IllegalArgumentException("AI summary cache requires positive durations and a valid version");
		}
	}
}
