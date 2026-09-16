package com.ssafy.thispatch.global.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

/** JWT 자체의 유효성만 검증한다. 회원 상태·Refresh Token 저장/폐기 여부는 호출 측의 책임이다. */
public class JwtTokenProvider {

	private static final String ISSUER = "thispatch";
	private static final String TOKEN_TYPE_CLAIM = "token_type";

	private final SecretKey key;
	private final Clock clock;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;
	private final JwtParser parser;

	public JwtTokenProvider(JwtProperties properties, Clock clock) {
		this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.secret()), "HmacSHA256");
		this.clock = clock;
		this.accessTokenTtl = properties.accessTokenTtl();
		this.refreshTokenTtl = properties.refreshTokenTtl();
		this.parser = Jwts.parser()
			.verifyWith(key)
			.sig().clear().add(Jwts.SIG.HS256).and()
			.clock(() -> Date.from(clock.instant()))
			.build();
	}

	public String issueAccessToken(long memberId) {
		return issue(memberId, TokenType.ACCESS, accessTokenTtl);
	}

	public String issueRefreshToken(long memberId) {
		return issue(memberId, TokenType.REFRESH, refreshTokenTtl);
	}

	public VerifiedToken validateAccessToken(String token) {
		return validate(token, TokenType.ACCESS);
	}

	public VerifiedToken validateRefreshToken(String token) {
		return validate(token, TokenType.REFRESH);
	}

	private String issue(long memberId, TokenType type, Duration ttl) {
		if (memberId <= 0) {
			throw new IllegalArgumentException("memberId must be positive");
		}
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		return Jwts.builder()
			.header().type("JWT").and()
			.issuer(ISSUER)
			.subject(Long.toString(memberId))
			.claim(TOKEN_TYPE_CLAIM, type.name())
			.id(UUID.randomUUID().toString())
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(issuedAt.plus(ttl)))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	private VerifiedToken validate(String token, TokenType expectedType) {
		if (token == null || token.isBlank()) {
			throw new TokenValidationException(Reason.INVALID);
		}
		try {
			Claims claims;
			try {
				claims = parser.parseSignedClaims(token).getPayload();
			} catch (ExpiredJwtException exception) {
				// JJWT가 서명을 검증한 claim만 사용한다. 만료된 다른 용도의 토큰도 INVALID로 구분한다.
				claims = exception.getClaims();
			}
			return validateClaims(claims, expectedType);
		} catch (JwtException | IllegalArgumentException exception) {
			// 원인 예외에는 token/claim 값이 들어갈 수 있으므로 외부로 전달하지 않는다.
			throw new TokenValidationException(Reason.INVALID);
		}
	}

	private VerifiedToken validateClaims(Claims claims, TokenType expectedType) {
		String subject = claims.getSubject();
		String tokenId = claims.getId();
		Date issuedAt = claims.getIssuedAt();
		Date expiration = claims.getExpiration();
		if (!ISSUER.equals(claims.getIssuer()) || !expectedType.name().equals(claims.get(TOKEN_TYPE_CLAIM, String.class))
			|| subject == null || !subject.matches("[1-9][0-9]*") || tokenId == null || tokenId.isBlank()
			|| issuedAt == null || expiration == null) {
			throw new TokenValidationException(Reason.INVALID);
		}
		long memberId = Long.parseLong(subject);
		Instant now = clock.instant();
		Instant issued = issuedAt.toInstant();
		Instant expires = expiration.toInstant();
		if (issued.isAfter(now) || !expires.isAfter(issued)) {
			throw new TokenValidationException(Reason.INVALID);
		}
		// exp와 현재 시각이 같아지는 순간부터 만료다.
		if (!now.isBefore(expires)) {
			throw new TokenValidationException(Reason.EXPIRED);
		}
		return new VerifiedToken(memberId, expectedType, tokenId, issued, expires);
	}
}
