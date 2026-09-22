package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.global.exception.BusinessException;

@Timeout(15)
class CaseSearchExecutorTest {

	private final CaseSearchExecutor tasks = new CaseSearchExecutor();

	@AfterEach
	void close() {
		tasks.close();
	}

	@Test
	void twoSearchesShareFourWorkersAndKeepInputOrder() throws Exception {
		var callers = Executors.newFixedThreadPool(2);
		var entered = new CountDownLatch(4);
		var release = new CountDownLatch(1);
		var active = new AtomicInteger();
		var maximum = new AtomicInteger();
		Function<Integer, Integer> operation = input -> {
			int running = active.incrementAndGet();
			maximum.accumulateAndGet(running, Math::max);
			entered.countDown();
			try {
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
				return input;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			} finally {
				active.decrementAndGet();
			}
		};
		var inputs = IntStream.range(0, 12).boxed().toList();
		try {
			var first = callers.submit(() -> tasks.map(inputs, operation));
			var second = callers.submit(() -> tasks.map(inputs, operation));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(active.get()).isEqualTo(4);
			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS)).containsExactlyElementsOf(inputs);
			assertThat(second.get(5, TimeUnit.SECONDS)).containsExactlyElementsOf(inputs);
			assertThat(maximum.get()).isEqualTo(4);
		} finally {
			release.countDown();
			callers.shutdownNow();
		}
	}

	@Test
	void failureDoesNotWaitForAnEarlierSlowTaskAndInterruptsIt() throws Exception {
		var started = new CountDownLatch(1);
		var interrupted = new CountDownLatch(1);
		var failure = new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		assertThatThrownBy(() -> tasks.map(List.of(0, 1), input -> {
			try {
				if (input == 0) {
					started.countDown();
					new CountDownLatch(1).await();
				} else {
					assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
					throw failure;
				}
				return input;
			} catch (InterruptedException exception) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
				return input;
			}
		})).isSameAs(failure);
		assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void maximumCandidateCountDoesNotOverflowTheQueue() {
		var inputs = IntStream.range(0, 600).boxed().toList();
		assertThat(tasks.map(inputs, Function.identity())).containsExactlyElementsOf(inputs);
	}

	@Test
	void shutdownCancelsQueuedWorkAndReleasesItsCaller() throws Exception {
		var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(tasks, "executor");
		var workersStarted = new CountDownLatch(4);
		var releaseWorkers = new CountDownLatch(1);
		var caller = Executors.newSingleThreadExecutor();
		try {
			// 실제 호출이 실행되기 전 종료되는 상황을 만들기 위해 네 작업자를 먼저 점유한다.
			for (int index = 0; index < 4; index++) {
				pool.execute(() -> {
					workersStarted.countDown();
					try {
						releaseWorkers.await();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
				});
			}
			assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
			var result = caller.submit(() -> tasks.map(List.of(1), Function.identity()));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (pool.getQueue().isEmpty() && System.nanoTime() < deadline) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
			}
			assertThat(pool.getQueue()).hasSize(1);
			tasks.close();
			assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
				.hasCauseInstanceOf(BusinessException.class);
		} finally {
			releaseWorkers.countDown();
			caller.shutdownNow();
		}
	}

	@Test
	void unavailableExecutorUsesExistingAiError() {
		tasks.close();
		assertThatThrownBy(() -> tasks.map(List.of(1), Function.identity()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
	}
}
