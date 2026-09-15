package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.thispatch.global.config.SteamLoginCodeConfig;
import com.ssafy.thispatch.global.config.SteamLoginCodeProperties;
import com.ssafy.thispatch.global.exception.BusinessException;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
class SteamLoginCodeServiceIntegrationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
		.withUserConfiguration(SteamLoginCodeConfig.class)
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("spring.profiles.active=test");

	@Test
	void storesOnlyHashAndValidationDoesNotExtendExpiryOrConsumeCode() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			var redis = context.getBean(StringRedisTemplate.class);
			requireTestDatabase(redis);
			var service = context.getBean(SteamLoginCodeService.class);
			String code = service.issue(42);
			String key = key(code);
			try {
				assertThat(redis.opsForValue().get(key)).isEqualTo("42");
				Long ttlBefore = redis.getExpire(key, TimeUnit.MILLISECONDS);
				assertThat(ttlBefore).isPositive().isLessThanOrEqualTo(300_000);
				assertThat(service.validate(code)).isEqualTo(42);
				assertThat(service.validate(code)).isEqualTo(42);
				assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttlBefore);
				assertThat(service.consume(code)).isEqualTo(42);
				assertThat(redis.hasKey(key)).isFalse();
				assertInvalid(() -> service.validate(code));
				assertInvalid(() -> service.consume(code));
			} finally {
				redis.delete(key);
			}
		});
	}

	@Test
	void redisExpiresCodeAndBothValidationAndConsumptionRejectIt() {
		runner.withPropertyValues("app.steam.login-code.ttl=1s").run(context -> {
			assertThat(context).hasNotFailed();
			var redis = context.getBean(StringRedisTemplate.class);
			requireTestDatabase(redis);
			var service = context.getBean(SteamLoginCodeService.class);
			String code = service.issue(42);
			String key = key(code);
			try {
				assertThat(service.validate(code)).isEqualTo(42);
				await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
					.until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
				assertInvalid(() -> service.validate(code));
				assertInvalid(() -> service.consume(code));
			} finally {
				redis.delete(key);
			}
		});
	}

	@Test
	void onlyOneConcurrentConsumerSucceedsAcrossServiceInstances() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			var redis = context.getBean(StringRedisTemplate.class);
			requireTestDatabase(redis);
			var first = context.getBean(SteamLoginCodeService.class);
			var second = new SteamLoginCodeService(redis, context.getBean(SteamLoginCodeProperties.class),
				new SecureRandom());
			String code = first.issue(42);
			String key = key(code);
			int contenders = 12;
			var executor = Executors.newFixedThreadPool(contenders);
			var ready = new CountDownLatch(contenders);
			var start = new CountDownLatch(1);
			var results = new ArrayList<Future<Boolean>>();
			try {
				for (int i = 0; i < contenders; i++) {
					var service = i % 2 == 0 ? first : second;
					results.add(executor.submit(() -> {
						ready.countDown();
						if (!start.await(5, TimeUnit.SECONDS)) {
							throw new AssertionError("Concurrent consumers did not start");
						}
						try {
							assertThat(service.consume(code)).isEqualTo(42);
							return true;
						} catch (BusinessException exception) {
							assertThat(exception.getErrorCode()).isEqualTo(STEAM_LOGIN_CODE_INVALID);
							return false;
						}
					}));
				}
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
				start.countDown();
				int successes = 0;
				for (Future<Boolean> result : results) {
					if (result.get(10, TimeUnit.SECONDS)) {
						successes++;
					}
				}
				assertThat(successes).isEqualTo(1);
				assertThat(redis.hasKey(key)).isFalse();
				assertInvalid(() -> second.consume(code));
			} finally {
				start.countDown();
				executor.shutdownNow();
				assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
				// 전체 DB를 비우지 않고 이 테스트에서 발급한 키만 정리한다.
				redis.delete(key);
			}
		});
	}

	private void requireTestDatabase(StringRedisTemplate redis) {
		var factory = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)
			redis.getConnectionFactory();
		assertThat(factory).isNotNull();
		assertThat(factory.getDatabase()).isEqualTo(15);
	}

	private String key(String code) throws Exception {
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(code.getBytes(StandardCharsets.UTF_8)));
		return "thispatch:auth:steam:login-code:" + hash;
	}

	private void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(STEAM_LOGIN_CODE_INVALID));
	}
}
