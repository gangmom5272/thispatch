package com.ssafy.thispatch.global.config;

import java.time.Duration;
import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
	String secret,
	@DefaultValue("HS256") String algorithm,
	@DefaultValue("15m") Duration accessTokenTtl,
	@DefaultValue("7d") Duration refreshTokenTtl
) {

	public JwtProperties {
		if (!"HS256".equals(algorithm)) {
			throw new IllegalArgumentException("app.jwt.algorithm must be HS256");
		}
		validateSecret(secret);
		validateTtl(accessTokenTtl, "app.jwt.access-token-ttl");
		validateTtl(refreshTokenTtl, "app.jwt.refresh-token-ttl");
	}

	private static void validateSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be configured");
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(secret);
		} catch (IllegalArgumentException exception) {
			// 설정 오류에 비밀키 원문이나 디코더 예외를 포함하지 않는다.
			throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be valid Base64");
		}
		if (decoded.length < 32) {
			throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must decode to at least 32 bytes for HS256");
		}
	}

	private static void validateTtl(Duration ttl, String property) {
		if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.getNano() != 0) {
			throw new IllegalArgumentException(property + " must be a positive duration in whole seconds");
		}
	}

	@Override
	public String toString() {
		return "JwtProperties[secret=<redacted>, algorithm=" + algorithm
			+ ", accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl=" + refreshTokenTtl + "]";
	}
}
