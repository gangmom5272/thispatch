package com.ssafy.thispatch.domain.patch.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.global.exception.BusinessException;

/** 검색 요청들이 같은 실행기를 공유해 카드·비교의 동시 외부 호출 수를 제한한다. */
@Component
public class CaseSearchExecutor {

	private static final int CONCURRENCY = 4;
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(CONCURRENCY, CONCURRENCY,
		0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
		new CustomizableThreadFactory("case-search-ai-"));

	public <T, R> List<R> map(List<T> inputs, Function<T, R> operation) {
		if (inputs.isEmpty()) return List.of();
		BlockingQueue<Future<IndexedResult<R>>> completion = new LinkedBlockingQueue<>();
		List<Future<IndexedResult<R>>> pending = new ArrayList<>();
		List<R> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
		int nextIndex = 0;
		try {
			// 한 검색이 후보 수만큼 대기열을 선점하지 않도록 최대 4개씩 보충한다.
			while (nextIndex < Math.min(CONCURRENCY, inputs.size())) {
				pending.add(submit(completion, inputs, operation, nextIndex++));
			}
			for (int completed = 0; completed < inputs.size(); completed++) {
				var result = completion.take().get();
				results.set(result.index(), result.value());
				if (nextIndex < inputs.size()) {
					pending.add(submit(completion, inputs, operation, nextIndex++));
				}
			}
			return List.copyOf(results);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE, exception);
		} catch (RejectedExecutionException | CancellationException exception) {
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE, exception);
		} catch (ExecutionException exception) {
			// 기존 AI 오류는 그대로 전달하고, 프로그래밍 오류를 AI 장애로 숨기지 않는다.
			if (exception.getCause() instanceof RuntimeException cause) throw cause;
			if (exception.getCause() instanceof Error cause) throw cause;
			throw new IllegalStateException("Case search task failed", exception.getCause());
		} finally {
			// 일부 실패 시 불완전한 성공을 반환하지 않고 남은 작업의 중단을 요청한다.
			for (var task : pending) {
				if (!task.isDone()) task.cancel(true);
			}
		}
	}

	private <T, R> Future<IndexedResult<R>> submit(BlockingQueue<Future<IndexedResult<R>>> completion,
		List<T> inputs, Function<T, R> operation, int index) {
		var task = new FutureTask<IndexedResult<R>>(() -> new IndexedResult<>(index, operation.apply(inputs.get(index)))) {
			@Override
			protected void done() {
				// 대기 중 취소된 작업도 완료를 알려 요청 스레드가 계속 기다리지 않게 한다.
				completion.add(this);
			}
		};
		executor.execute(task);
		return task;
	}

	@PreDestroy
	public void close() {
		for (Runnable queued : executor.shutdownNow()) {
			if (queued instanceof Future<?> task) task.cancel(true);
		}
	}

	private record IndexedResult<R>(int index, R value) {}
}
