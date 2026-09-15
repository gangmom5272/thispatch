package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;

class RefreshTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
	private static final JwtProperties PROPERTIES = new JwtProperties(
		"dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlLWluLXByb2Q=", "HS256",
		Duration.ofMinutes(15), Duration.ofDays(7));
	private final MemberRepository repository = mock(MemberRepository.class);
	private final JwtTokenProvider provider = at(NOW);
	private final RefreshTokenService service = new RefreshTokenService(repository, provider);

	@Test
	void storesOnlyHashAndVerifiedExpiration() {
		String token = provider.issueRefreshToken(42L);
		Instant expiry = provider.validateRefreshToken(token).expiresAt();
		when(repository.updateRefreshToken(eq(42L), anyString(), eq(expiry))).thenReturn(1);

		service.store(42L, token);

		var hash = ArgumentCaptor.forClass(String.class);
		verify(repository).updateRefreshToken(eq(42L), hash.capture(), eq(expiry));
		assertThat(hash.getValue()).matches("[0-9a-f]{64}").isNotEqualTo(token);
	}

	@Test
	void rejectsStoringTokenForAnotherMember() {
		assertReason(() -> service.store(43L, provider.issueRefreshToken(42L)), Reason.INVALID);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsStoringTokenForMissingMember() {
		assertReason(() -> service.store(42L, provider.issueRefreshToken(42L)), Reason.INVALID);
	}

	@Test
	void rejectsMissingMemberWhenValidating() {
		when(repository.findById(42L)).thenReturn(Optional.empty());
		assertReason(() -> service.validate(provider.issueRefreshToken(42L)), Reason.INVALID);
	}

	@Test
	void rejectsTokenWhichHasNeverBeenStored() {
		when(repository.findById(42L)).thenReturn(Optional.of(Member.builder().status("ACTIVE").build()));
		assertReason(() -> service.validate(provider.issueRefreshToken(42L)), Reason.INVALID);
	}

	@Test
	void rejectsAnotherMembersRevokeWithoutChangingStorage() {
		assertReason(() -> service.revoke(43L, provider.issueRefreshToken(42L)), Reason.INVALID);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsExpiredTokensBeforeAccessingStorage() {
		String token = at(NOW.minus(Duration.ofDays(7))).issueRefreshToken(42L);
		assertReason(() -> service.store(42L, token), Reason.EXPIRED);
		assertReason(() -> service.validate(token), Reason.EXPIRED);
		assertReason(() -> service.revoke(42L, token), Reason.EXPIRED);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsAccessTokensBeforeAccessingStorage() {
		String token = provider.issueAccessToken(42L);
		assertReason(() -> service.store(42L, token), Reason.INVALID);
		assertReason(() -> service.validate(token), Reason.INVALID);
		assertReason(() -> service.revoke(42L, token), Reason.INVALID);
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "invalid.jwt.token"})
	void rejectsInvalidTokensBeforeAccessingStorage(String token) {
		assertReason(() -> service.store(42L, token), Reason.INVALID);
		assertReason(() -> service.validate(token), Reason.INVALID);
		assertReason(() -> service.revoke(42L, token), Reason.INVALID);
		verifyNoInteractions(repository);
	}

	private JwtTokenProvider at(Instant time) {
		return new JwtTokenProvider(PROPERTIES, Clock.fixed(time, ZoneOffset.UTC));
	}

	private void assertReason(Runnable action, Reason reason) {
		assertThatThrownBy(action::run).isInstanceOf(TokenValidationException.class)
			.satisfies(exception -> assertThat(((TokenValidationException)exception).getReason()).isEqualTo(reason));
	}
}
