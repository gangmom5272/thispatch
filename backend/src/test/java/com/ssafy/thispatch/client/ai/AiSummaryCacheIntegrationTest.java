package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.ssafy.thispatch.global.exception.BusinessException;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
class AiSummaryCacheIntegrationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("spring.profiles.active=test");

	@Test
	void reusesValidatedResultAcrossInstancesAndExpiresWithoutExtendingTtl() {
		withRedis((redis, version) -> {
			var first = cache(redis, version, Duration.ofSeconds(2));
			var second = cache(redis, version, Duration.ofSeconds(2));
			var calls = new AtomicInteger();
			assertThat(first.getOrCompute("reviews", "input", String.class,
				() -> "summary-" + calls.incrementAndGet(), value -> true)).isEqualTo("summary-1");
			String key = onlyResultKey(redis, version);
			long ttlBefore = redis.getExpire(key, TimeUnit.MILLISECONDS);
			assertThat(second.getOrCompute("reviews", "input", String.class,
				() -> "summary-" + calls.incrementAndGet(), value -> true)).isEqualTo("summary-1");
			assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttlBefore);
			assertThat(calls).hasValue(1);
			await().atMost(Duration.ofSeconds(4)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
			assertThat(second.getOrCompute("reviews", "input", String.class,
				() -> "summary-" + calls.incrementAndGet(), value -> true)).isEqualTo("summary-2");
		});
	}

	@Test
	void realReviewResultRoundTripsAndUncleanCachedResultIsRejected() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			var request = request("LANGUAGE", "schinese", "review");
			var result = new AiReviewClient.Summary(730, "LANGUAGE", "schinese", "제목", "정상 한국어 요약",
				List.of("전투"), List.of(1L), 3, "qwen", 1, true, 100);
			var calls = new AtomicInteger();
			for (int attempt = 0; attempt < 2; attempt++) {
				assertThat(cache.getOrCompute("reviews", request, AiReviewClient.Summary.class, () -> {
					calls.incrementAndGet();
					return result;
				}, value -> AiReviewClient.isUsableSummary(request, value))).isEqualTo(result);
			}
			assertThat(calls).hasValue(1);
			String key = onlyResultKey(redis, version);
			String invalid = redis.opsForValue().get(key).replace("\"clean\":true", "\"clean\":false");
			redis.opsForValue().set(key, invalid, Duration.ofSeconds(10));
			assertThat(cache.getOrCompute("reviews", request, AiReviewClient.Summary.class, () -> {
				calls.incrementAndGet();
				return result;
			}, value -> AiReviewClient.isUsableSummary(request, value))).isEqualTo(result);
			assertThat(calls).hasValue(2);
		});
	}

	@Test
	void periodReviewTextScopeAndVersionChangesNeverReuseOldSummary() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			var calls = new AtomicInteger();
			var inputs = List.of(
				List.of("2026-09-21", request("LANGUAGE", "schinese", "original")),
				List.of("2026-09-22", request("LANGUAGE", "schinese", "original")),
				List.of("2026-09-21", request("LANGUAGE", "schinese", "edited")),
				List.of("2026-09-21", request("LANGUAGE", "english", "original")),
				List.of("2026-09-21", request("BAND", "1", "original")));
			for (Object input : inputs) {
				cache.getOrCompute("reviews", input, String.class, () -> "summary-" + calls.incrementAndGet(), value -> true);
			}
			cache(redis, version + "changed", Duration.ofMinutes(30)).getOrCompute("reviews", inputs.get(0),
				String.class, () -> "summary-" + calls.incrementAndGet(), value -> true);
			assertThat(calls).hasValue(6);
		});
	}

	@Test
	void trendStatisticsChangesInvalidateCachedInput() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			var calls = new AtomicInteger();
			for (int positive : List.of(20, 21)) {
				var request = new AiTrendClient.Request(730,
					List.of(new AiTrendClient.Day(LocalDate.of(2026, 9, 21), 30, positive, 30, positive, 0, 0)),
					List.of(), 7, true);
				cache.getOrCompute("trends", request, String.class, () -> "summary-" + calls.incrementAndGet(), value -> true);
			}
			assertThat(calls).hasValue(2);
		});
	}

	@Test
	void concurrentInstancesGenerateOnlyOnce() {
		withRedis((redis, version) -> {
			var ownerCache = cache(redis, version, Duration.ofMinutes(30));
			var followerCache = cache(redis, version, Duration.ofMinutes(30));
			var calls = new AtomicInteger();
			var entered = new CountDownLatch(1);
			var finish = new CountDownLatch(1);
			var first = CompletableFuture.supplyAsync(() -> ownerCache.getOrCompute("reviews", "same", String.class, () -> {
				calls.incrementAndGet();
				entered.countDown();
				awaitLatch(finish);
				return "shared";
			}, value -> true));
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			var follower = new CompletableFuture<String>();
			Thread thread = new Thread(() -> {
				try {
					follower.complete(followerCache.getOrCompute("reviews", "same", String.class, () -> {
						calls.incrementAndGet();
						return "duplicate";
					}, value -> true));
				} catch (Throwable exception) {
					follower.completeExceptionally(exception);
				}
			});
			thread.start();
			try {
				await().atMost(Duration.ofSeconds(2)).until(() -> thread.getState() == Thread.State.TIMED_WAITING);
				assertThat(calls).hasValue(1);
			} finally {
				finish.countDown();
			}
			assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("shared");
			assertThat(follower.get(3, TimeUnit.SECONDS)).isEqualTo("shared");
			assertThat(calls).hasValue(1);
		});
	}

	@Test
	void failureAndFallbackAreNotStoredAndNextRequestCanRecover() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			assertThatThrownBy(() -> cache.getOrCompute("reviews", "same", String.class, () -> {
				throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
			}, value -> true)).isInstanceOf(BusinessException.class);
			assertThat(redis.keys(prefix(version) + "*")).isEmpty();
			assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "fallback", value -> false))
				.isEqualTo("fallback");
			assertThat(redis.keys(prefix(version) + "*")).isEmpty();
			assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "recovered", value -> true))
				.isEqualTo("recovered");
		});
	}

	@Test
	void corruptOrInvalidCachedResultIsRegenerated() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			cache.getOrCompute("reviews", "same", String.class, () -> "valid", "valid"::equals);
			String key = onlyResultKey(redis, version);
			for (String damaged : List.of("not json", "\"invalid\"", "null")) {
				redis.opsForValue().set(key, damaged, Duration.ofSeconds(10));
				assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "valid", "valid"::equals))
					.isEqualTo("valid");
				assertThat(redis.opsForValue().get(key)).isEqualTo("\"valid\"");
			}
		});
	}

	@Test
	void expiredOwnerCannotOverwriteResultOrDeleteReplacementLock() {
		withRedis((redis, version) -> {
			var cache = cache(redis, version, Duration.ofMinutes(30));
			cache.getOrCompute("reviews", "same", String.class, () -> {
				String lockKey = redis.keys(prefix(version) + "*:lock").iterator().next();
				redis.opsForValue().set(lockKey, "replacement-owner", Duration.ofSeconds(5));
				return "late-result";
			}, value -> true);
			Set<String> keys = redis.keys(prefix(version) + "*");
			assertThat(keys).hasSize(1);
			String lockKey = keys.iterator().next();
			assertThat(lockKey).endsWith(":lock");
			assertThat(redis.opsForValue().get(lockKey)).isEqualTo("replacement-owner");
		});
	}

	private void withRedis(RedisCheck check) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			var redis = context.getBean(StringRedisTemplate.class);
			var factory = (LettuceConnectionFactory)redis.getConnectionFactory();
			assertThat(factory).isNotNull();
			assertThat(factory.getDatabase()).isEqualTo(15);
			String version = "test-" + UUID.randomUUID();
			try {
				check.run(redis, version);
			} finally {
				// 무작위 테스트 접두어에 속한 키만 정리한다. FLUSHDB는 사용하지 않는다.
				Set<String> keys = redis.keys(prefix(version) + "*");
				if (keys != null && !keys.isEmpty()) {
					redis.delete(keys);
				}
			}
		});
	}

	private static AiSummaryCache cache(StringRedisTemplate redis, String version, Duration ttl) {
		return new AiSummaryCache(redis, new ObjectMapper().findAndRegisterModules(),
			new AiSummaryCacheProperties(true, ttl, version, Duration.ofSeconds(3)),
			new AiProperties("http://ai.test", null, null));
	}

	private static String onlyResultKey(StringRedisTemplate redis, String version) {
		Set<String> keys = redis.keys(prefix(version) + "*");
		assertThat(keys).hasSize(1);
		return keys.iterator().next();
	}

	private static String prefix(String version) {
		return "thispatch:ai:summary:" + version;
	}

	private static AiReviewClient.SummaryRequest request(String type, String scope, String text) {
		return new AiReviewClient.SummaryRequest(730, "CS2", type, scope,
			List.of(new AiReviewClient.Review(1, text, true, 10, "schinese", 60),
				new AiReviewClient.Review(2, text, false, 9, "schinese", 120),
				new AiReviewClient.Review(3, text, true, 8, "schinese", 180)));
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for test generation");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@FunctionalInterface
	private interface RedisCheck {
		void run(StringRedisTemplate redis, String version) throws Exception;
	}
}
