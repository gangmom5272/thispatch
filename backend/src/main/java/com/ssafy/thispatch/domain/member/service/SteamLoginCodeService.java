package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.thispatch.global.config.SteamLoginCodeProperties;
import com.ssafy.thispatch.global.exception.BusinessException;

/**
 * Steam 토큰 교환 전용 코드. 회원 존재·상태 확인과 JWT 발급은 호출 API의 책임이다.
 * Redis 소비는 DB 트랜잭션과 독립적이며 후속 처리 실패 시에도 복구하지 않는다.
 */
public class SteamLoginCodeService {

	private static final String KEY_PREFIX = "thispatch:auth:steam:login-code:";
	private static final Pattern CODE_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final int MAX_ISSUE_ATTEMPTS = 3;

	private final StringRedisTemplate redis;
	private final SteamLoginCodeProperties properties;
	private final SecureRandom random;

	public SteamLoginCodeService(StringRedisTemplate redis, SteamLoginCodeProperties properties,
		SecureRandom random) {
		this.redis = redis;
		this.properties = properties;
		this.random = random;
	}

	/** 회원 저장이 커밋된 후 발급한다. 코드 원문은 반환만 하고 저장·로깅하지 않는다. */
	public String issue(long memberId) {
		if (memberId <= 0) {
			throw new IllegalArgumentException("memberId must be positive");
		}
		for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
			byte[] bytes = new byte[32];
			random.nextBytes(bytes);
			String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			// NX와 TTL을 하나의 SET으로 적용한다. 충돌한 기존 코드와 만료 시각은 유지한다.
			Boolean stored = redis.opsForValue().setIfAbsent(key(code), Long.toString(memberId), properties.ttl());
			if (Boolean.TRUE.equals(stored)) {
				return code;
			}
			if (stored == null) {
				throw new IllegalStateException("Steam login code storage did not return a result");
			}
		}
		throw new IllegalStateException("Steam login code generation attempts exhausted");
	}

	/**
	 * 소비 없이 현재 유효성만 확인한다. TTL을 연장하지 않으며 이후 소비 성공을 보장하지 않는다.
	 * 토큰 발급에는 반드시 consume의 반환값을 사용해야 한다.
	 */
	public long validate(String code) {
		requireFormat(code);
		return memberId(redis.opsForValue().get(key(code)));
	}

	/** 검증·소비를 GETDEL 한 번으로 수행한다. 동시 요청 중 하나만 회원 ID를 받는다. */
	public long consume(String code) {
		requireFormat(code);
		return memberId(redis.opsForValue().getAndDelete(key(code)));
	}

	private void requireFormat(String code) {
		if (code == null || !CODE_FORMAT.matcher(code).matches()) {
			throw new BusinessException(STEAM_LOGIN_CODE_INVALID);
		}
	}

	private long memberId(String stored) {
		if (stored == null) {
			// 미발급·만료·이미 소비됨을 외부에 구분해서 노출하지 않는다.
			throw new BusinessException(STEAM_LOGIN_CODE_INVALID);
		}
		try {
			long memberId = Long.parseLong(stored);
			if (memberId > 0) {
				return memberId;
			}
		} catch (NumberFormatException ignored) {
			// 저장값이나 파싱 예외의 원문을 노출하지 않는다.
		}
		throw new IllegalStateException("Invalid stored Steam login code member ID");
	}

	private String key(String code) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
			return KEY_PREFIX + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
