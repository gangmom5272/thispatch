package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderTest {

	@Test
	void providesEncoderThatHashesAndVerifiesPasswordsWithoutWebContext() {
		new ApplicationContextRunner()
			.withUserConfiguration(AppConfig.class)
			.withPropertyValues(
				"app.frontend-base-url=http://localhost:5173",
				"app.backend-public-url=http://localhost:8080",
				"app.cors.allowed-origins=http://localhost:5173")
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(PasswordEncoder.class);
				PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
				String rawPassword = "member-password";
				String hash = encoder.encode(rawPassword);

				assertThat(hash).isNotEqualTo(rawPassword).hasSize(60);
				assertThat(encoder.encode(rawPassword)).isNotEqualTo(hash);
				assertThat(encoder.matches(rawPassword, hash)).isTrue();
				assertThat(encoder.matches("wrong-password", hash)).isFalse();
			});
	}
}
