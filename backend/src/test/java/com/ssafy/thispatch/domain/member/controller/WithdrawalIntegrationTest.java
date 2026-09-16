package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.domain.member.service.SteamLoginCodeService;
import com.ssafy.thispatch.domain.member.service.SteamMemberService;
import com.ssafy.thispatch.domain.member.service.WithdrawalService;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalIntegrationTest {

	private static final String PASSWORD = "withdrawal-test-password";
	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@MockitoSpyBean private WithdrawalService withdrawalService;
	@MockitoSpyBean private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@Autowired private RefreshTokenService refreshTokens;
	@Autowired private PasswordEncoder encoder;
	@Autowired private SteamLoginCodeService codes;
	@Autowired private SteamMemberService steamMembers;
	@Autowired private StringRedisTemplate redis;
	@Autowired private PlatformTransactionManager transactions;

	private final List<Long> memberIds = new ArrayList<>();
	private final List<Long> gameIds = new ArrayList<>();
	private final List<String> codeKeys = new ArrayList<>();

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@AfterEach
	void cleanOnlyOwnFixtures() {
		codeKeys.forEach(redis::delete);
		memberIds.forEach(id -> {
			jdbc.update("delete from my_game where member_id = ?", id);
			jdbc.update("delete from member where member_id = ?", id);
		});
		gameIds.forEach(id -> jdbc.update("delete from game where appid = ?", id));
	}

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void withdrawsCurrentMemberPreservingProfileAndRelatedDataAndBlockingAllRemainingCredentials(LoginType type)
		throws Exception {
		Member member = newMember(type);
		long id = member.getMemberId();
		long otherId = newMember(LoginType.LOCAL).getMemberId();
		String otherRefresh = tokens.issueRefreshToken(otherId);
		refreshTokens.store(otherId, otherRefresh);
		String oldRefresh = tokens.issueRefreshToken(id);
		String currentRefresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, oldRefresh);
		refreshTokens.store(id, currentRefresh);
		String firstAccess = tokens.issueAccessToken(id);
		String secondAccess = tokens.issueAccessToken(id);
		long gameId = -Math.abs(UUID.randomUUID().getMostSignificantBits());
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'withdrawal fixture', now())", gameId);
		gameIds.add(gameId);
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", id, gameId);
		var relatedBefore = jdbc.queryForMap("select * from my_game where member_id = ? and appid = ?", id, gameId);
		var before = row(id);
		Instant startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		// 사용자 입력의 다른 회원 ID는 사용하지 않는다. Refresh Token 없이 현재 회원만 탈퇴한다.
		var response = mvc.perform(delete("/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccess)
			.param("memberId", Long.toString(otherId)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"memberId\":" + otherId + "}")).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.get("code").asText()).isEqualTo("200");
		assertThat(body.get("message").asText()).isEqualTo("회원탈퇴가 완료되었습니다.");
		assertThat(body.get("success").asBoolean()).isTrue();
		assertThat(body.has("data")).isFalse();
		Instant respondedAt = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(startedAt, Instant.now());
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertWithdrawn(id);
		var after = row(id);
		for (String column : List.of("member_id", "login_type", "email", "password", "steam_id", "nickname", "created_at")) {
			assertThat(after.get(column)).isEqualTo(before.get(column));
		}
		assertThat(members.findById(id).orElseThrow().getUpdatedAt()).isBetween(startedAt, Instant.now());
		assertThat(jdbc.queryForMap("select * from my_game where member_id = ? and appid = ?", id, gameId)).isEqualTo(relatedBefore);
		assertThat(refreshTokens.validate(otherRefresh).getMemberId()).isEqualTo(otherId);

		for (String access : List.of(firstAccess, secondAccess)) {
			// JWT 서명 자체는 살아 있어도 매 요청의 DB 상태 검사에서 거부한다.
			assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(id);
			assertError(withdraw(access), 401, "UNAUTHORIZED");
			assertError(mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andReturn().getResponse(), 401, "UNAUTHORIZED");
			var session = mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andReturn().getResponse();
			assertThat(session.getStatus()).isEqualTo(200);
			var sessionBody = mapper.readTree(session.getContentAsByteArray());
			assertThat(sessionBody.at("/data/authenticated").asBoolean()).isFalse();
			assertThat(sessionBody.at("/data/user").isNull()).isTrue();
		}
		for (String refresh : List.of(oldRefresh, currentRefresh)) {
			assertError(postJson("/auth/refresh", Map.of("refreshToken", refresh)), 401, "REFRESH_TOKEN_INVALID");
		}
		if (type == LoginType.LOCAL) {
			assertError(postJson("/auth/login", Map.of("email", member.getEmail(), "password", PASSWORD)), 401, "LOGIN_FAILED");
			assertError(postJson("/auth/signup", Map.of("email", member.getEmail(), "password", PASSWORD, "nickname", "new")),
				409, "EMAIL_ALREADY_REGISTERED");
		} else {
			assertThat(steamMembers.findOrCreate(member.getSteamId()).getMemberId()).isEqualTo(id);
		}
		assertThat(row(id)).isEqualTo(after);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
	void remainingSteamLoginCodeIsConsumedButCannotIssueTokensAfterWithdrawal() throws Exception {
		long id = newMember(LoginType.STEAM).getMemberId();
		String code = issueCode(id);
		assertThat(withdraw(tokens.issueAccessToken(id)).getStatus()).isEqualTo(200);
		assertError(postJson("/auth/steam/token", Map.of("loginCode", code)), 401, "STEAM_LOGIN_CODE_INVALID");
		assertThat(redis.hasKey(codeKeys.get(0))).isFalse();
		assertWithdrawn(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "EXPIRED"})
	void withdrawalDoesNotRequireAStoredOrUnexpiredRefreshToken(String state) throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		if (state.equals("EXPIRED")) {
			var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
			new TransactionTemplate(transactions).executeWithoutResult(transaction ->
				new RefreshTokenService(members, past).store(id, past.issueRefreshToken(id)));
		}
		assertThat(withdraw(tokens.issueAccessToken(id)).getStatus()).isEqualTo(200);
		assertWithdrawn(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WITHDRAWN", "UNKNOWN", "INACTIVE"})
	void missingAndNonActiveMembersAreRejectedWithoutChangingData(String state) throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		if (state.equals("MISSING")) {
			jdbc.update("delete from member where member_id = ?", id);
		} else {
			jdbc.update("update member set status = ? where member_id = ?", state, id);
		}
		var before = jdbc.queryForList("select * from member where member_id = ?", id);
		assertError(withdraw(tokens.issueAccessToken(id)), 401, "UNAUTHORIZED");
		assertThat(jdbc.queryForList("select * from member where member_id = ?", id)).isEqualTo(before);
	}

	@Test
	void invalidMissingExpiredAndWrongPurposeAccessCannotWithdraw() throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);
		var before = row(id);
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
		assertError(mvc.perform(delete("/members/me")).andReturn().getResponse(), 401, "UNAUTHORIZED");
		for (String token : List.of("invalid-token", refresh, past.issueAccessToken(id))) {
			assertError(withdraw(token), 401, "UNAUTHORIZED");
		}
		String bearer = "Bearer " + tokens.issueAccessToken(id);
		assertError(mvc.perform(delete("/members/me").header(HttpHeaders.AUTHORIZATION, bearer, bearer))
			.andReturn().getResponse(), 401, "UNAUTHORIZED");
		assertThat(row(id)).isEqualTo(before);
	}

	@Test
	void failureAfterUpdateRollsBackStatusTimestampAndTokenTogether() throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);
		var before = row(id);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertThat(row(id).get("status")).isEqualTo("WITHDRAWN");
			assertThat(row(id).get("refresh_token_hash")).isNull();
			throw new DataAccessResourceFailureException("private-withdrawal-detail");
		}).when(withdrawalService).withdraw(eq(new MemberPrincipal(id)));
		assertError(withdraw(tokens.issueAccessToken(id)), 500, "INTERNAL_SERVER_ERROR");
		assertThat(row(id)).isEqualTo(before);
		assertThat(refreshTokens.validate(refresh).getMemberId()).isEqualTo(id);
	}

	@Test
	void simultaneousWithdrawalsThatPassedAuthenticationHaveExactlyOneSuccess() throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		String access = tokens.issueAccessToken(id);
		var ready = new CountDownLatch(2);
		doAnswer(invocation -> {
			ready.countDown();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			return invocation.callRealMethod();
		}).when(withdrawalService).withdraw(eq(new MemberPrincipal(id)));
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> withdraw(access));
			var second = executor.submit(() -> withdraw(access));
			assertThat(List.of(first.get(20, TimeUnit.SECONDS).getStatus(), second.get(20, TimeUnit.SECONDS).getStatus()))
				.containsExactlyInAnyOrder(200, 401);
			assertWithdrawn(id);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void withdrawalBeforeConcurrentLocalLoginStorageDoesNotRestoreTokens() throws Exception {
		assertWithdrawalBeforeTokenStorage(LoginType.LOCAL);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
	void withdrawalBeforeConcurrentSteamExchangeStorageDoesNotRestoreTokens() throws Exception {
		assertWithdrawalBeforeTokenStorage(LoginType.STEAM);
	}

	private void assertWithdrawalBeforeTokenStorage(LoginType type) throws Exception {
		Member member = newMember(type);
		long id = member.getMemberId();
		String access = tokens.issueAccessToken(id);
		String code = type == LoginType.STEAM ? issueCode(id) : null;
		var checked = new CountDownLatch(1);
		var withdrawn = new CountDownLatch(1);
		// 로그인 회원 조회는 통과했지만 토큰 저장은 아직 시작하지 않은 경합을 재현한다.
		doAnswer(invocation -> {
			checked.countDown();
			assertThat(withdrawn.await(10, TimeUnit.SECONDS)).isTrue();
			return invocation.callRealMethod();
		}).when(tokens).issueRefreshToken(id);
		var executor = Executors.newSingleThreadExecutor();
		try {
			var login = executor.submit(() -> type == LoginType.LOCAL
				? postJson("/auth/login", Map.of("email", member.getEmail(), "password", PASSWORD))
				: postJson("/auth/steam/token", Map.of("loginCode", code)));
			assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(withdraw(access).getStatus()).isEqualTo(200);
			withdrawn.countDown();
			assertError(login.get(15, TimeUnit.SECONDS), 401,
				type == LoginType.LOCAL ? "LOGIN_FAILED" : "STEAM_LOGIN_CODE_INVALID");
			assertWithdrawn(id);
		} finally {
			withdrawn.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void withdrawalAfterConcurrentTokenStorageClearsTheCommittedToken() throws Exception {
		long id = newMember(LoginType.LOCAL).getMemberId();
		String refresh = tokens.issueRefreshToken(id);
		var stored = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var attempted = new CountDownLatch(1);
		doAnswer(invocation -> {
			attempted.countDown();
			return invocation.callRealMethod();
		}).when(withdrawalService).withdraw(eq(new MemberPrincipal(id)));
		var executor = Executors.newFixedThreadPool(2);
		try {
			var login = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				refreshTokens.store(id, refresh);
				stored.countDown();
				try {
					assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
			}));
			assertThat(stored.await(10, TimeUnit.SECONDS)).isTrue();
			var withdrawal = executor.submit(() -> withdraw(tokens.issueAccessToken(id)));
			assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			login.get(15, TimeUnit.SECONDS);
			assertThat(withdrawal.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
			assertWithdrawn(id);
			assertThatThrownBy(() -> refreshTokens.validate(refresh)).isInstanceOf(TokenValidationException.class);
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Member newMember(LoginType type) {
		Member member = members.saveAndFlush(Member.builder().loginType(type).status("ACTIVE")
			.email(type == LoginType.LOCAL ? UUID.randomUUID() + "@example.com" : null)
			.password(type == LoginType.LOCAL ? encoder.encode(PASSWORD) : null)
			.steamId(type == LoginType.STEAM ? new BigInteger(63, new SecureRandom()) : null)
			.nickname(type == LoginType.LOCAL ? "보존할 닉네임" : null).createdAt(Instant.now()).build());
		memberIds.add(member.getMemberId());
		return member;
	}

	private String issueCode(long id) throws Exception {
		assertThat(redis.getConnectionFactory()).isInstanceOf(LettuceConnectionFactory.class);
		assertThat(((LettuceConnectionFactory)redis.getConnectionFactory()).getDatabase()).isEqualTo(15);
		String code = codes.issue(id);
		codeKeys.add("thispatch:auth:steam:login-code:" + HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))));
		return code;
	}

	private Map<String, Object> row(long id) {
		return jdbc.queryForMap("select * from member where member_id = ?", id);
	}

	private void assertWithdrawn(long id) {
		Member member = members.findById(id).orElseThrow();
		assertThat(member.getStatus()).isEqualTo("WITHDRAWN");
		assertThat(member.getUpdatedAt()).isNotNull();
		assertThat(member.getRefreshTokenHash()).isNull();
		assertThat(member.getRefreshTokenExpiresAt()).isNull();
	}

	private MockHttpServletResponse withdraw(String access) throws Exception {
		return mvc.perform(delete("/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
			.andReturn().getResponse();
	}

	private MockHttpServletResponse postJson(String path, Map<String, String> body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
			.andReturn().getResponse();
	}

	private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.get("code").asText()).isEqualTo(code);
		assertThat(body.get("message").asText()).isNotBlank();
		assertThat(body.get("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.toString()).doesNotContain("private-withdrawal-detail", PASSWORD, "Exception", "accessToken", "refreshToken");
		if (status == 401) {
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
	}
}
