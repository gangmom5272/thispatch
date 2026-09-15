package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AppConfig.class)
		.withPropertyValues(
			"app.frontend-base-url=https://thispatch.example",
			"app.backend-public-url=https://thispatch.example/api",
			"app.cors.allowed-origins=https://thispatch.example,http://localhost:5173");

	@Test
	void bindsPublicUrlsAndMultipleExactOrigins() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			var properties = context.getBean(AppProperties.class);
			assertThat(properties.backendPublicUrl().getPath()).isEqualTo("/api");
			assertThat(properties.cors().allowedOrigins()).hasSize(2);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"app.frontend-base-url=", "app.backend-public-url=", "app.cors.allowed-origins="})
	void refusesMissingRequiredSettings(String property) {
		runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/api", "ftp://thispatch.example", "https://user:password@thispatch.example",
		"https://thispatch.example?redirect=other", "https://thispatch.example#fragment",
		"https://thispatch.example:70000"
	})
	void refusesInvalidPublicUrl(String value) {
		runner.withPropertyValues("app.backend-public-url=" + value)
			.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"*", "null", "https://*.example", "https://thispatch.example/api", "http://localhost:5173/"})
	void refusesWildcardOrNonOriginCorsValues(String value) {
		runner.withPropertyValues("app.cors.allowed-origins=" + value)
			.run(context -> assertThat(context).hasFailed());
	}
}
