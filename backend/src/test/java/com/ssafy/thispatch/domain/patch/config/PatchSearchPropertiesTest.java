package com.ssafy.thispatch.domain.patch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class PatchSearchPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PatchSearchConfig.class)
		.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	void usesExistingDefaultWhenEnvironmentVariableIsAbsent() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(PatchSearchProperties.class);
			assertThat(context.getBean(PatchSearchProperties.class).efSearch()).isEqualTo(100);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 40, 60, 1000})
	void readsEnvironmentVariableIncludingBoundaryValues(int value) {
		withEnvironmentValue(Integer.toString(value)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(PatchSearchProperties.class).efSearch()).isEqualTo(value);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "1001", "2147483648", "40.5", "invalid", ""})
	void rejectsInvalidEnvironmentVariableAtStartup(String value) {
		withEnvironmentValue(value).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("app.patch-search");
		});
	}

	private ApplicationContextRunner withEnvironmentValue(String value) {
		return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
			new SystemEnvironmentPropertySource("patch-search-test-environment",
				Map.of("PATCH_SEARCH_EF_SEARCH", value))));
	}
}
