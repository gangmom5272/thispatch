package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.config.SteamLoginCodeProperties;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
class SteamTokenServiceIntegrationTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private MemberRepository members;
	@Autowired
	private SteamLoginCodeService codes;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private StringRedisTemplate redis;
	@MockitoSpyBean
	private RefreshTokenService refresh;

	private final List<Long> memberIds = new ArrayList<>();
	private final List<String> codeKeys = new ArrayList<>();

	@BeforeEach
	void requireTestDatabases() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		assertThat(((LettuceConnectionFactory)redis.getConnectionFactory()).getDatabase()).isEqualTo(15);
	}

	@AfterEach
	void cleanOnlyOwnFixtures() {
		codeKeys.forEach(redis::delete);
		memberIds.forEach(members::deleteById);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"기존 닉네임"})
	void exchangesOnceAndReplacesStoredRefreshToken(String nickname) throws Exception {
		long id = newMember(nickname);
		String previous = tokens.issueRefreshToken(id);
		refresh.store(id, previous);
		String code = issue(id, codes);

		var result = exchange(code);
		assertThat(result.status()).isEqualTo(200);
		JsonNode data = result.body().get("data");
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.has("nickname")).isTrue();
		assertThat(data.get("nickname")).isEqualTo(mapper.valueToTree(nickname));
		String access = data.get("accessToken").asText();
		String refreshToken = data.get("refreshToken").asText();
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(id);
		assertThat(refresh.validate(refreshToken).getMemberId()).isEqualTo(id);
		assertThat(refresh.validate(refreshToken).getRefreshTokenHash()).isNotEqualTo(refreshToken);
		assertThatThrownBy(() -> refresh.validate(previous)).isInstanceOf(TokenValidationException.class);
		assertThat(exchange(code).status()).isEqualTo(401);
		assertThat(refresh.validate(refreshToken).getMemberId()).isEqualTo(id);
	}

	@Test
	void expiredCodeCannotIssueOrReplaceTokens() throws Exception {
		long id = newMember(null);
		String previous = tokens.issueRefreshToken(id);
		refresh.store(id, previous);
		var shortLivedCodes = new SteamLoginCodeService(redis,
			new SteamLoginCodeProperties(Duration.ofSeconds(1)), new SecureRandom());
		String code = issue(id, shortLivedCodes);
		await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
			.until(() -> !Boolean.TRUE.equals(redis.hasKey(key(code))));
		var result = exchange(code);
		assertThat(result.status()).isEqualTo(401);
		assertThat(result.body().get("code").asText()).isEqualTo("STEAM_LOGIN_CODE_INVALID");
		assertThat(refresh.validate(previous).getMemberId()).isEqualTo(id);
	}

	@Test
	void onlyOneConcurrentHttpExchangeSucceeds() throws Exception {
		long id = newMember(null);
		String code = issue(id, codes);
		int contenders = 6;
		var executor = Executors.newFixedThreadPool(contenders);
		var ready = new CountDownLatch(contenders);
		var start = new CountDownLatch(1);
		var results = new ArrayList<Future<ExchangeResult>>();
		try {
			for (int i = 0; i < contenders; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
					return exchange(code);
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int successes = 0;
			for (var result : results) {
				var response = result.get(15, TimeUnit.SECONDS);
				if (response.status() == 200) {
					successes++;
					assertThat(refresh.validate(response.body().get("data").get("refreshToken").asText())
						.getMemberId()).isEqualTo(id);
				} else {
					assertThat(response.status()).isEqualTo(401);
					assertThat(response.body().get("code").asText()).isEqualTo("STEAM_LOGIN_CODE_INVALID");
				}
			}
			assertThat(successes).isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void failureAfterRefreshWriteRollsBackStorageButDoesNotRestoreCode() throws Exception {
		long id = newMember(null);
		String previous = tokens.issueRefreshToken(id);
		refresh.store(id, previous);
		String code = issue(id, codes);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new DataAccessResourceFailureException("simulated failure after token update");
		}).when(refresh).store(eq(id), anyString());

		assertThat(exchange(code).status()).isEqualTo(500);
		assertThat(refresh.validate(previous).getMemberId()).isEqualTo(id);
		assertThat(exchange(code).status()).isEqualTo(401);
	}

	@Test
	void missingMemberConsumesCodeWithoutIssuingTokens() throws Exception {
		long id = newMember(null);
		String code = issue(id, codes);
		members.deleteById(id);
		memberIds.remove(id);
		assertThat(exchange(code).status()).isEqualTo(401);
		assertThatThrownBy(() -> codes.consume(code)).isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode()).isEqualTo(STEAM_LOGIN_CODE_INVALID));
	}

	@Test
	void malformedCodeIsRejectedByRealCodeService() throws Exception {
		var result = exchange("malformed-code");
		assertThat(result.status()).isEqualTo(401);
		assertThat(result.body().get("code").asText()).isEqualTo("STEAM_LOGIN_CODE_INVALID");
	}

	private long newMember(String nickname) {
		// 실제 콜백처럼 회원 저장 커밋 후 코드를 발급한다.
		Member member = members.saveAndFlush(Member.builder().loginType(LoginType.STEAM)
			.steamId(new BigInteger(63, new SecureRandom())).nickname(nickname).status("ACTIVE")
			.createdAt(Instant.now()).build());
		memberIds.add(member.getMemberId());
		return member.getMemberId();
	}

	private String issue(long id, SteamLoginCodeService issuer) throws Exception {
		String code = issuer.issue(id);
		codeKeys.add(key(code));
		return code;
	}

	private String key(String code) throws Exception {
		return "thispatch:auth:steam:login-code:" + HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private ExchangeResult exchange(String code) throws Exception {
		var response = mvc.perform(post("/auth/steam/token").contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(java.util.Map.of("loginCode", code))))
			.andReturn().getResponse();
		return new ExchangeResult(response.getStatus(), mapper.readTree(response.getContentAsByteArray()));
	}

	private record ExchangeResult(int status, JsonNode body) {
	}
}
