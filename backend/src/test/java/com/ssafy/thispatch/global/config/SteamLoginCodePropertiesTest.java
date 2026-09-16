package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.thispatch.domain.member.service.SteamLoginCodeService;

class SteamLoginCodePropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SteamLoginCodeConfig.class)
		.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

	@Test
	void bindsFiveMinuteDefaultAndRegistersService() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(SteamLoginCodeService.class);
			assertThat(context.getBean(SteamLoginCodeProperties.class).ttl()).isEqualTo(Duration.ofMinutes(5));
		});
	}

	@Test
	void readsEnvironmentPlaceholderOverride() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("STEAM_LOGIN_CODE_TTL=1m").run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(SteamLoginCodeProperties.class).ttl()).isEqualTo(Duration.ofMinutes(1));
			});
	}

	@ParameterizedTest
	@ValueSource(strings = {"0s", "-1s", "1ms", "invalid", "9223372036854776s"})
	void rejectsInvalidExpiration(String ttl) {
		runner.withPropertyValues("app.steam.login-code.ttl=" + ttl)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void rejectsMissingTtlForDirectConstruction() {
		assertThatThrownBy(() -> new SteamLoginCodeProperties(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testProfilePinsTtlDespiteEnvironmentOverride() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("spring.profiles.active=test", "STEAM_LOGIN_CODE_TTL=1m")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(SteamLoginCodeProperties.class).ttl()).isEqualTo(Duration.ofMinutes(5));
			});
	}
}
