package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
class RedisConnectionIntegrationTest {

	@Test
	void connectsToRedisUsingTestProfile() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("spring.profiles.active=test")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var template = context.getBean(StringRedisTemplate.class);
				String response = template.execute((RedisCallback<String>) connection -> connection.ping());
				assertThat(response).isEqualTo("PONG");
			});
	}
}
