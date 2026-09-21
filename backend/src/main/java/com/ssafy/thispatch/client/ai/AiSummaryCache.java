package com.ssafy.thispatch.client.ai;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/** 성공한 요약만 저장하고, 같은 입력의 진행 중 작업을 공유한다. */
@Component
@Slf4j
public class AiSummaryCache {

	private static final long POLL_MILLIS = 100;
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
		    return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);
	private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
		    redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
		    return 1
		end
		return 0
		""", Long.class);

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final AiSummaryCacheProperties properties;
	private final AiProperties aiProperties;
	private final Duration lockTtl;
	private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

	public AiSummaryCache(StringRedisTemplate redis, ObjectMapper mapper,
		AiSummaryCacheProperties properties, AiProperties aiProperties) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
		this.aiProperties = aiProperties;
		// health와 요약 연결·응답 제한보다 길게 둔다. 프로세스가 죽어도 잠금은 만료된다.
		this.lockTtl = aiProperties.readTimeout().plus(aiProperties.connectTimeout().multipliedBy(2))
			.plusSeconds(20);
	}

	public <T> T getOrCompute(String kind, Object input, Class<T> resultType,
		Supplier<T> generate, Predicate<T> cacheable) {
		String key = key(kind, input);
		var pending = new CompletableFuture<Object>();
		CompletableFuture<Object> existing = inFlight.putIfAbsent(key, pending);
		if (existing != null) {
			return await(existing, resultType);
		}
		try {
			T result = properties.enabled()
				? loadOrGenerate(key, resultType, generate, cacheable) : generate.get();
			pending.complete(result);
			return result;
		} catch (RuntimeException | Error exception) {
			pending.completeExceptionally(exception);
			throw exception;
		} finally {
			inFlight.remove(key, pending);
		}
	}

	private <T> T loadOrGenerate(String key, Class<T> resultType, Supplier<T> generate,
		Predicate<T> cacheable) {
		String lockKey = key + ":lock";
		String owner = UUID.randomUUID().toString();
		long started = System.nanoTime();
		while (true) {
			boolean acquired;
			try {
				T cached = read(key, resultType, cacheable);
				if (cached != null) {
					return cached;
				}
				acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, owner, lockTtl));
			} catch (DataAccessException exception) {
				cacheUnavailable("read/lock", exception);
				// Redis 장애 때도 기존 기능은 유지한다. 같은 JVM의 중복 방지는 계속 적용된다.
				return generate.get();
			}
			if (acquired) {
				try {
					// 최초 조회와 잠금 획득 사이에 다른 서버가 완료했을 수 있다.
					try {
						T cached = read(key, resultType, cacheable);
						if (cached != null) {
							return cached;
						}
					} catch (DataAccessException exception) {
						cacheUnavailable("read", exception);
					}
					T result = generate.get();
					if (cacheable.test(result)) {
						publish(key, lockKey, owner, result);
					}
					return result;
				} finally {
					release(lockKey, owner);
				}
			}
			if (System.nanoTime() - started >= properties.waitTimeout().toNanos()) {
				throw unavailable(new TimeoutException("AI summary is still being generated"));
			}
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw unavailable(exception);
			}
		}
	}

	private <T> T read(String key, Class<T> resultType, Predicate<T> cacheable) {
		String json = redis.opsForValue().get(key);
		if (json == null) {
			return null;
		}
		try {
			T result = mapper.readValue(json, resultType);
			if (result != null && cacheable.test(result)) {
				return result;
			}
		} catch (JsonProcessingException exception) {
			log.warn("Ignoring malformed AI summary cache entry: type={}", resultType.getSimpleName());
		}
		// 캐시의 손상·구버전 데이터는 화면 오류로 만들지 않고 다시 생성한다.
		return null;
	}

	private void publish(String key, String lockKey, String owner, Object result) {
		try {
			String json = mapper.writeValueAsString(result);
			// 만료된 잠금의 이전 소유자가 뒤늦게 결과를 덮어쓰지 않도록 확인한다.
			redis.execute(PUBLISH, List.of(lockKey, key), owner, json, Long.toString(properties.ttl().toMillis()));
		} catch (JsonProcessingException exception) {
			log.warn("AI summary cache serialization failed: type={}", result.getClass().getSimpleName());
		} catch (DataAccessException exception) {
			cacheUnavailable("write", exception);
		}
	}

	private void release(String lockKey, String owner) {
		try {
			redis.execute(RELEASE, List.of(lockKey), owner);
		} catch (DataAccessException exception) {
			cacheUnavailable("unlock", exception);
		}
	}

	private <T> T await(CompletableFuture<Object> pending, Class<T> resultType) {
		try {
			return resultType.cast(pending.get(properties.waitTimeout().toMillis(), TimeUnit.MILLISECONDS));
		} catch (InterruptedException exception) {
			// 한 요청의 취소가 같은 결과를 기다리는 다른 요청까지 취소하지 않게 한다.
			Thread.currentThread().interrupt();
			throw unavailable(exception);
		} catch (TimeoutException exception) {
			throw unavailable(exception);
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException cause) {
				throw cause;
			}
			if (exception.getCause() instanceof Error cause) {
				throw cause;
			}
			throw new IllegalStateException("AI summary generation failed", exception.getCause());
		}
	}

	private String key(String kind, Object input) {
		try {
			byte[] json = mapper.writeValueAsBytes(List.of(aiProperties.baseUrl(), input));
			String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
			// 중괄호는 결과·잠금 키를 Redis Cluster에서도 같은 슬롯에 배치한다.
			return "thispatch:ai:summary:" + properties.version() + ":" + kind + ":{" + fingerprint + "}";
		} catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Cannot identify AI summary input", exception);
		}
	}

	private static BusinessException unavailable(Exception cause) {
		return new BusinessException(AiErrorCode.AI_UNAVAILABLE, cause);
	}

	private static void cacheUnavailable(String operation, DataAccessException exception) {
		log.warn("AI summary cache unavailable: operation={}, cause={}", operation,
			exception.getClass().getSimpleName());
	}
}
