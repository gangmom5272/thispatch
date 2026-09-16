package com.ssafy.thispatch.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.steam.login-code")
public record SteamLoginCodeProperties(@DefaultValue("5m") Duration ttl) {

	public SteamLoginCodeProperties {
		if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.getNano() != 0
			|| ttl.getSeconds() > Long.MAX_VALUE / 1000) {
			throw new IllegalArgumentException("app.steam.login-code.ttl must be a positive duration in whole seconds");
		}
	}
}
