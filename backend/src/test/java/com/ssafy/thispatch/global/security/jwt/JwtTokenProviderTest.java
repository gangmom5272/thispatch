package com.ssafy.thispatch.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

class JwtTokenProviderTest {

	private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
	private static final byte[] KEY_BYTES = "test-only-jwt-secret-never-use-in-prod".getBytes(StandardCharsets.UTF_8);
	private static final SecretKey KEY = new SecretKeySpec(KEY_BYTES, "HmacSHA256");
	private static final JwtProperties PROPERTIES = new JwtProperties(Base64.getEncoder().encodeToString(KEY_BYTES),
		"HS256", Duration.ofMinutes(15), Duration.ofDays(7));
	private final JwtTokenProvider provider = at(NOW);

	@ParameterizedTest
	@EnumSource(TokenType.class)
	void issuesSignedTokensWithExpectedClaimsAndLifetime(TokenType type) {
		String token = issue(provider, type, Long.MAX_VALUE);
		var parsed = Jwts.parser().verifyWith(KEY).clock(() -> Date.from(NOW)).build().parseSignedClaims(token);
		assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS256");
		assertThat(parsed.getHeader().getType()).isEqualTo("JWT");
		assertThat(parsed.getPayload().keySet()).containsExactlyInAnyOrder("iss", "sub", "token_type", "jti", "iat", "exp");
		assertThat(parsed.getPayload().getIssuer()).isEqualTo("thispatch");
		assertThat(parsed.getPayload().getSubject()).isEqualTo(Long.toString(Long.MAX_VALUE));
		var verified = validate(provider, type, token);
		assertThat(verified.memberId()).isEqualTo(Long.MAX_VALUE);
		assertThat(verified.type()).isEqualTo(type);
		assertThat(verified.tokenId()).isNotBlank();
		assertThat(verified.issuedAt()).isEqualTo(NOW);
		assertThat(verified.expiresAt()).isEqualTo(NOW.plus(ttl(type)));
	}

	@ParameterizedTest
	@EnumSource(TokenType.class)
	void eachIssuanceHasDistinctTokenIdEvenWithinSameSecond(TokenType type) {
		var first = validate(provider, type, issue(provider, type, 1));
		var second = validate(provider, type, issue(provider, type, 1));
		assertThat(first.tokenId()).isNotEqualTo(second.tokenId());
	}

	@ParameterizedTest
	@EnumSource(TokenType.class)
	void isValidUntilImmediatelyBeforeExpiryAndExpiresAtBoundary(TokenType type) {
		String token = issue(provider, type, 1);
		Instant expiry = NOW.plus(ttl(type));
		assertThat(validate(at(expiry.minusMillis(1)), type, token).memberId()).isEqualTo(1);
		assertReason(() -> validate(at(expiry), type, token), Reason.EXPIRED);
		assertReason(() -> validate(at(expiry.plusSeconds(1)), type, token), Reason.EXPIRED);
	}

	@Test
	void truncatesIssuanceToJwtSecondPrecision() {
		var fractionalClockProvider = at(NOW.plusMillis(987));
		var verified = provider.validateAccessToken(fractionalClockProvider.issueAccessToken(1));
		assertThat(verified.issuedAt()).isEqualTo(NOW);
		assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(900));
	}

	@Test
	void honorsConfiguredLifetimes() {
		var properties = new JwtProperties(PROPERTIES.secret(), "HS256", Duration.ofMinutes(5), Duration.ofDays(2));
		var configured = new JwtTokenProvider(properties, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(configured.validateAccessToken(configured.issueAccessToken(1)).expiresAt()).isEqualTo(NOW.plusSeconds(300));
		assertThat(configured.validateRefreshToken(configured.issueRefreshToken(1)).expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
	}

	@Test
	void accessAndRefreshCannotBeSubstitutedEvenIfExpired() {
		String access = provider.issueAccessToken(1);
		String refresh = provider.issueRefreshToken(1);
		assertReason(() -> provider.validateAccessToken(refresh), Reason.INVALID);
		assertReason(() -> provider.validateRefreshToken(access), Reason.INVALID);
		assertReason(() -> at(NOW.plus(Duration.ofDays(8))).validateAccessToken(refresh), Reason.INVALID);
		assertReason(() -> at(NOW.plus(Duration.ofDays(8))).validateRefreshToken(access), Reason.INVALID);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not-a-jwt", "a.b.c", "Bearer token", "a.b.c.d"})
	void rejectsMalformedInputs(String token) {
		assertReason(() -> provider.validateAccessToken(token), Reason.INVALID);
		assertReason(() -> provider.validateRefreshToken(token), Reason.INVALID);
	}

	@Test
	void rejectsTamperedPayloadAndSignature() {
		String token = provider.issueAccessToken(1);
		String[] parts = token.split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String modified = payload.replace("\"sub\":\"1\"", "\"sub\":\"2\"");
		assertThat(modified).isNotEqualTo(payload);
		String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
			.encodeToString(modified.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
		assertReason(() -> provider.validateAccessToken(tampered), Reason.INVALID);
		String signature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
		assertReason(() -> provider.validateAccessToken(parts[0] + "." + parts[1] + "." + signature), Reason.INVALID);
	}

	@Test
	void rejectsDifferentSigningKey() {
		var otherKey = new SecretKeySpec(new byte[32], "HmacSHA256");
		String token = baseBuilder().signWith(otherKey, Jwts.SIG.HS256).compact();
		assertReason(() -> provider.validateAccessToken(token), Reason.INVALID);
	}

	@Test
	void rejectsUnsignedTokens() {
		String token = baseBuilder().compact();
		assertReason(() -> provider.validateAccessToken(token), Reason.INVALID);
	}

	@Test
	void rejectsHs512EvenWithSameKeyAndAlwaysIssuesHs256() {
		byte[] bytes = new byte[64];
		var longKeyProperties = new JwtProperties(Base64.getEncoder().encodeToString(bytes), "HS256",
			Duration.ofMinutes(15), Duration.ofDays(7));
		var longKeyProvider = new JwtTokenProvider(longKeyProperties, Clock.fixed(NOW, ZoneOffset.UTC));
		var longKey = new SecretKeySpec(bytes, "HmacSHA256");
		String hs512 = baseBuilder().signWith(longKey, Jwts.SIG.HS512).compact();
		assertReason(() -> longKeyProvider.validateAccessToken(hs512), Reason.INVALID);
		String issued = longKeyProvider.issueAccessToken(1);
		var parsed = Jwts.parser().verifyWith(longKey).clock(() -> Date.from(NOW)).build().parseSignedClaims(issued);
		assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS256");
	}

	@ParameterizedTest
	@ValueSource(strings = {"iss", "sub", "token_type", "jti", "iat", "exp"})
	void rejectsMissingRequiredClaims(String claim) {
		String token = signed(builder -> builder.claim(claim, null));
		assertReason(() -> provider.validateAccessToken(token), Reason.INVALID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808", "+1", " 1", "01"})
	void rejectsInvalidMemberId(String subject) {
		String token = signed(builder -> builder.subject(subject));
		assertReason(() -> provider.validateAccessToken(token), Reason.INVALID);
	}

	@Test
	void rejectsWrongIssuerAndUnsupportedPurpose() {
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.issuer("other-service"))), Reason.INVALID);
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.claim("token_type", "SIGNUP"))), Reason.INVALID);
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.claim("token_type", 1))), Reason.INVALID);
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.id(" "))), Reason.INVALID);
	}

	@Test
	void rejectsFutureIssuanceNotBeforeAndReversedLifetime() {
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.issuedAt(Date.from(NOW.plusSeconds(1))))), Reason.INVALID);
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.notBefore(Date.from(NOW.plusSeconds(1))))), Reason.INVALID);
		assertReason(() -> provider.validateAccessToken(signed(builder -> builder.expiration(Date.from(NOW)))), Reason.INVALID);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsInvalidMemberIdAtIssuance(long memberId) {
		assertThatThrownBy(() -> provider.issueAccessToken(memberId)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider.issueRefreshToken(memberId)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validationFailureDoesNotExposeTokenClaimsOrLibraryException() {
		String token = signed(builder -> builder.issuer("private-issuer-value"));
		assertThatThrownBy(() -> provider.validateAccessToken(token))
			.isInstanceOf(TokenValidationException.class)
			.hasMessage("Token is invalid").hasNoCause()
			.satisfies(exception -> assertThat(exception.toString()).doesNotContain(token, "private-issuer-value", PROPERTIES.secret()));
		String expired = provider.issueAccessToken(1);
		assertThatThrownBy(() -> at(NOW.plusSeconds(901)).validateAccessToken(expired))
			.isInstanceOf(TokenValidationException.class).hasMessage("Token has expired").hasNoCause();
	}

	private JwtTokenProvider at(Instant time) {
		return new JwtTokenProvider(PROPERTIES, Clock.fixed(time, ZoneOffset.UTC));
	}

	private Duration ttl(TokenType type) {
		return type == TokenType.ACCESS ? Duration.ofMinutes(15) : Duration.ofDays(7);
	}

	private String issue(JwtTokenProvider target, TokenType type, long memberId) {
		return type == TokenType.ACCESS ? target.issueAccessToken(memberId) : target.issueRefreshToken(memberId);
	}

	private VerifiedToken validate(JwtTokenProvider target, TokenType type, String token) {
		return type == TokenType.ACCESS ? target.validateAccessToken(token) : target.validateRefreshToken(token);
	}

	private JwtBuilder baseBuilder() {
		return Jwts.builder().issuer("thispatch").subject("1").claim("token_type", "ACCESS")
			.id("test-token-id").issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(900)));
	}

	private String signed(Consumer<JwtBuilder> customizer) {
		var builder = baseBuilder();
		customizer.accept(builder);
		return builder.signWith(KEY, Jwts.SIG.HS256).compact();
	}

	private void assertReason(Runnable action, Reason reason) {
		assertThatThrownBy(action::run).isInstanceOf(TokenValidationException.class)
			.satisfies(exception -> assertThat(((TokenValidationException)exception).getReason()).isEqualTo(reason));
	}
}
