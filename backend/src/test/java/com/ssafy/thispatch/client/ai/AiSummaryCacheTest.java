package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.thispatch.global.exception.BusinessException;

class AiSummaryCacheTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final AiSummaryCache cache = new AiSummaryCache(redis, new ObjectMapper(),
		new AiSummaryCacheProperties(false, Duration.ofMinutes(30), "v1", Duration.ofSeconds(2)),
		new AiProperties("http://ai.test", null, null));

	@Test
	void concurrentRequestsShareOneResultAndCompletedWorkIsRemoved() throws Exception {
		var calls = new AtomicInteger();
		var entered = new CountDownLatch(1);
		var finish = new CountDownLatch(1);
		var first = CompletableFuture.supplyAsync(() -> cache.getOrCompute("reviews", "same", String.class, () -> {
			calls.incrementAndGet();
			entered.countDown();
			awaitLatch(finish);
			return "summary";
		}, value -> true));
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		var second = new CompletableFuture<String>();
		Thread follower = new Thread(() -> {
			try {
				second.complete(cache.getOrCompute("reviews", "same", String.class, () -> {
					calls.incrementAndGet();
					return "duplicate";
				}, value -> true));
			} catch (Throwable exception) {
				second.completeExceptionally(exception);
			}
		});
		follower.start();
		try {
			await().atMost(Duration.ofSeconds(1)).until(() -> follower.getState() == Thread.State.TIMED_WAITING);
		} finally {
			finish.countDown();
		}
		assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("summary");
		assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("summary");
		assertThat(calls).hasValue(1);
		assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "new", value -> true)).isEqualTo("new");
		verifyNoInteractions(redis);
	}

	@Test
	void concurrentFailureIsSharedWithoutRetryingAndNextRequestCanRecover() throws Exception {
		var failure = new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		var entered = new CountDownLatch(1);
		var finish = new CountDownLatch(1);
		var first = CompletableFuture.supplyAsync(() -> cache.getOrCompute("reviews", "same", String.class, () -> {
			entered.countDown();
			awaitLatch(finish);
			throw failure;
		}, value -> true));
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		var second = new CompletableFuture<String>();
		Thread follower = new Thread(() -> {
			try {
				second.complete(cache.getOrCompute("reviews", "same", String.class, () -> "duplicate", value -> true));
			} catch (Throwable exception) {
				second.completeExceptionally(exception);
			}
		});
		follower.start();
		try {
			await().atMost(Duration.ofSeconds(1)).until(() -> follower.getState() == Thread.State.TIMED_WAITING);
		} finally {
			finish.countDown();
		}
		assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS)).hasCause(failure);
		assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS)).hasCause(failure);
		assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "recovered", value -> true))
			.isEqualTo("recovered");
	}

	@Test
	void generationFailureKeepsItsErrorAndAllowsNextRequest() {
		var failure = new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		assertThatThrownBy(() -> cache.getOrCompute("reviews", "same", String.class, () -> {
			throw failure;
		}, value -> true)).isSameAs(failure);
		assertThat(cache.getOrCompute("reviews", "same", String.class, () -> "recovered", value -> true))
			.isEqualTo("recovered");
	}

	@Test
	void redisConnectionFailureFallsBackToNormalGeneration() {
		when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("unavailable"));
		var enabled = new AiSummaryCache(redis, new ObjectMapper(),
			new AiSummaryCacheProperties(true, Duration.ofMinutes(30), "v1", Duration.ofSeconds(2)),
			new AiProperties("http://ai.test", null, null));
		assertThat(enabled.getOrCompute("reviews", "same", String.class, () -> "summary", value -> true))
			.isEqualTo("summary");
	}

	@Test
	void waitingRequestCanTimeOutWithoutCancelingOwner() throws Exception {
		var shortWait = new AiSummaryCache(redis, new ObjectMapper(),
			new AiSummaryCacheProperties(false, Duration.ofMinutes(30), "v1", Duration.ofMillis(100)),
			new AiProperties("http://ai.test", null, null));
		var entered = new CountDownLatch(1);
		var finish = new CountDownLatch(1);
		var owner = CompletableFuture.supplyAsync(() -> shortWait.getOrCompute("reviews", "same", String.class, () -> {
			entered.countDown();
			awaitLatch(finish);
			return "summary";
		}, value -> true));
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		try {
			assertThatThrownBy(() -> shortWait.getOrCompute("reviews", "same", String.class,
				() -> "duplicate", value -> true)).isInstanceOf(BusinessException.class);
			assertThat(owner.isDone()).isFalse();
		} finally {
			finish.countDown();
		}
		assertThat(owner.get(2, TimeUnit.SECONDS)).isEqualTo("summary");
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
}
