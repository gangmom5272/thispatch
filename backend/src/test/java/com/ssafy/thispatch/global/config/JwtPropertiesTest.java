package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class JwtPropertiesTest {

	private static final String TEST_SECRET = Base64.getEncoder()
		.encodeToString("test-only-jwt-secret-never-use-in-prod".getBytes(StandardCharsets.UTF_8));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(JwtConfig.class);

	@Test
	void bindsApprovedDefaultsAndRedactsSecret() {
		runner.withPropertyValues("app.jwt.secret=" + TEST_SECRET).run(context -> {
			assertThat(context).hasNotFailed();
			var properties = context.getBean(JwtProperties.class);
			assertThat(properties.algorithm()).isEqualTo("HS256");
			assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
			assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
			assertThat(properties.toString()).contains("<redacted>").doesNotContain(TEST_SECRET);
		});
	}

	@Test
	void readsYamlPlaceholdersAndTtlOverrides() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("JWT_SECRET=" + TEST_SECRET, "JWT_ACCESS_TOKEN_TTL=20m", "JWT_REFRESH_TOKEN_TTL=3d")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var properties = context.getBean(JwtProperties.class);
				assertThat(properties.secret()).isEqualTo(TEST_SECRET);
				assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(20));
				assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(3));
			});
	}

	@Test
	void rejectsMissingSecret() {
		runner.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not-a-base64-secret!", "c2hvcnQ="})
	void rejectsInvalidSecretWithoutLoggingIt(String secret, CapturedOutput output) {
		runner.withPropertyValues("app.jwt.secret=" + secret).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
			if (!secret.isEmpty()) {
				assertThat(output.getAll()).doesNotContain(secret);
				assertThat(context.getStartupFailure()).hasStackTraceContaining("app.jwt.secret");
				assertThat(context.getStartupFailure().getMessage()).doesNotContain(secret);
			}
		});
	}

	@Test
	void acceptsExactly256BitKey() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		runner.withPropertyValues("app.jwt.secret=" + key).run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void rejectsKeyBelow256Bits() {
		String key = Base64.getEncoder().encodeToString(new byte[31]);
		runner.withPropertyValues("app.jwt.secret=" + key).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"none", "HS512", "RS256"})
	void rejectsOtherAlgorithms(String algorithm) {
		runner.withPropertyValues("app.jwt.secret=" + TEST_SECRET, "app.jwt.algorithm=" + algorithm)
			.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"0s", "-1s", "1ms", "invalid"})
	void rejectsInvalidTtls(String ttl) {
		for (String property : new String[] {"app.jwt.access-token-ttl", "app.jwt.refresh-token-ttl"}) {
			runner.withPropertyValues("app.jwt.secret=" + TEST_SECRET, property + "=" + ttl)
				.run(context -> assertThat(context).hasFailed());
		}
	}
}
