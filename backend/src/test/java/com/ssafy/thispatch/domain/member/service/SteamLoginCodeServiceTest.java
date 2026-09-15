package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.global.config.SteamLoginCodeProperties;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;

class SteamLoginCodeServiceTest {

	private static final Duration TTL = Duration.ofMinutes(5);
	private static final String CODE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);
	private final SecureRandom random = mock(SecureRandom.class);
	private final SteamLoginCodeService service = new SteamLoginCodeService(redis,
		new SteamLoginCodeProperties(TTL), random);

	@BeforeEach
	void setUp() {
		when(redis.opsForValue()).thenReturn(values);
	}

	@Test
	void issues256BitUrlSafeCodeAndStoresOnlyHashWithMemberAndTtl() throws Exception {
		when(values.setIfAbsent(anyString(), eq("42"), eq(TTL))).thenReturn(true);
		String code = service.issue(42);

		assertThat(code).matches("[A-Za-z0-9_-]{43}").isEqualTo(CODE);
		assertThat(Base64.getUrlDecoder().decode(code)).hasSize(32);
		var key = ArgumentCaptor.forClass(String.class);
		verify(values).setIfAbsent(key.capture(), eq("42"), eq(TTL));
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(code.getBytes(StandardCharsets.UTF_8)));
		assertThat(key.getValue()).isEqualTo("thispatch:auth:steam:login-code:" + hash).doesNotContain(code);
		verify(random).nextBytes(any(byte[].class));
	}

	@Test
	void collisionRetriesWithNewRandomCodeWithoutOverwritingExistingEntry() {
		AtomicInteger attempt = new AtomicInteger();
		doAnswer(invocation -> {
			Arrays.fill((byte[])invocation.getArgument(0), (byte)attempt.incrementAndGet());
			return null;
		}).when(random).nextBytes(any(byte[].class));
		when(values.setIfAbsent(anyString(), eq("42"), eq(TTL))).thenReturn(false, true);

		assertThat(service.issue(42)).isNotBlank();
		var keys = ArgumentCaptor.forClass(String.class);
		verify(values, times(2)).setIfAbsent(keys.capture(), eq("42"), eq(TTL));
		assertThat(keys.getAllValues()).doesNotHaveDuplicates();
	}

	@Test
	void persistentCollisionStopsAfterBoundedAttempts() {
		when(values.setIfAbsent(anyString(), eq("42"), eq(TTL))).thenReturn(false);
		assertThatThrownBy(() -> service.issue(42)).isInstanceOf(IllegalStateException.class);
		verify(values, times(3)).setIfAbsent(anyString(), eq("42"), eq(TTL));
	}

	@Test
	void missingStorageAcknowledgementDoesNotReturnACodeOrRetry() {
		when(values.setIfAbsent(anyString(), eq("42"), eq(TTL))).thenReturn(null);
		assertThatThrownBy(() -> service.issue(42)).isInstanceOf(IllegalStateException.class);
		verify(values).setIfAbsent(anyString(), eq("42"), eq(TTL));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsInvalidMemberIdBeforeStorage(long memberId) {
		assertThatThrownBy(() -> service.issue(memberId)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(values, random);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "invalid", "jwt.access.token", "jwt.refresh.token",
		"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!"})
	void rejectsMalformedAndOtherPurposeTokensWithoutStorageAccess(String code) {
		assertInvalid(() -> service.validate(code));
		assertInvalid(() -> service.consume(code));
		verifyNoInteractions(values);
	}

	@Test
	void rejectsUnissuedExpiredOrConsumedCodeWithSameSafeBusinessError() {
		assertInvalid(() -> service.validate(CODE));
		assertInvalid(() -> service.consume(CODE));
	}

	@Test
	void validationDoesNotConsumeOrExtendTtlAndConsumptionUsesAtomicOperation() {
		when(values.get(anyString())).thenReturn("42");
		when(values.getAndDelete(anyString())).thenReturn("42", (String)null);
		assertThat(service.validate(CODE)).isEqualTo(42);
		assertThat(service.validate(CODE)).isEqualTo(42);
		assertThat(service.consume(CODE)).isEqualTo(42);
		assertInvalid(() -> service.consume(CODE));
		verify(values, times(2)).get(anyString());
		verify(values, times(2)).getAndDelete(anyString());
		org.mockito.Mockito.verifyNoMoreInteractions(values);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "private-corrupt-value", "9223372036854775808"})
	void corruptedStorageFailsWithoutExposingValue(String stored) {
		when(values.get(anyString())).thenReturn(stored);
		when(values.getAndDelete(anyString())).thenReturn(stored);
		assertThatThrownBy(() -> service.validate(CODE)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Invalid stored Steam login code member ID").hasNoCause();
		assertThatThrownBy(() -> service.consume(CODE)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Invalid stored Steam login code member ID").hasNoCause();
	}

	@Test
	void storageFailuresAreNotMisreportedAsInvalidCodeAndAreNotRetried() {
		var failure = new RedisConnectionFailureException("Redis unavailable");
		when(values.setIfAbsent(anyString(), eq("42"), eq(TTL))).thenThrow(failure);
		when(values.get(anyString())).thenThrow(failure);
		when(values.getAndDelete(anyString())).thenThrow(failure);
		assertThatThrownBy(() -> service.issue(42)).isSameAs(failure);
		assertThatThrownBy(() -> service.validate(CODE)).isSameAs(failure);
		assertThatThrownBy(() -> service.consume(CODE)).isSameAs(failure);
		verify(values).setIfAbsent(anyString(), eq("42"), eq(TTL));
		verify(values).get(anyString());
		verify(values).getAndDelete(anyString());
	}

	@Test
	void invalidCodeUsesExistingErrorResponseContract() {
		var response = new GlobalExceptionHandler()
			.handleBusinessException(new BusinessException(STEAM_LOGIN_CODE_INVALID));
		assertThat(response.getStatusCode().value()).isEqualTo(401);
		JsonNode body = new ObjectMapper().valueToTree(response.getBody());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.get("code").asText()).isEqualTo("STEAM_LOGIN_CODE_INVALID");
		assertThat(body.get("message").asText()).isEqualTo("Steam 로그인을 다시 진행해주세요.");
		assertThat(body.get("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.has("errors") || body.has("data") || body.has("success")).isFalse();
	}

	private void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.hasMessage("Steam 로그인을 다시 진행해주세요.")
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(STEAM_LOGIN_CODE_INVALID));
	}
}
