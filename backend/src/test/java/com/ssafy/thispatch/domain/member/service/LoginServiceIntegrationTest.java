package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginServiceIntegrationTest {

	private static final String PASSWORD = " 로그인 Password ";
	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private RefreshTokenService refresh;

	private final List<Long> memberIds = new ArrayList<>();

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@AfterEach
	void cleanOnlyOwnFixtures() {
		memberIds.forEach(members::deleteById);
	}

	@Test
	void verifiesStoredCharHashAndReplacesRefreshWhileOldAccessRemainsValid() throws Exception {
		Member member = newMember();
		long id = member.getMemberId();
		String oldAccess = tokens.issueAccessToken(id);
		String oldRefresh = tokens.issueRefreshToken(id);
		refresh.store(id, oldRefresh);

		var result = login(" " + member.getEmail().toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD);
		assertThat(result.status()).isEqualTo(200);
		String access = result.body().at("/data/accessToken").asText();
		String refreshToken = result.body().at("/data/refreshToken").asText();
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(id);
		Member stored = refresh.validate(refreshToken);
		assertThat(stored.getMemberId()).isEqualTo(id);
		assertThat(stored.getRefreshTokenHash()).matches("[0-9a-f]{64}").isNotEqualTo(refreshToken);
		assertThat(stored.getRefreshTokenExpiresAt()).isEqualTo(tokens.validateRefreshToken(refreshToken).expiresAt());
		assertThatThrownBy(() -> refresh.validate(oldRefresh)).isInstanceOf(TokenValidationException.class);
		assertThat(tokens.validateAccessToken(oldAccess).memberId()).isEqualTo(id);
		assertThat(encoder.matches(PASSWORD, stored.getPassword())).isTrue();
		assertThat(stored.getNickname()).isEqualTo(member.getNickname());
		assertThat(stored.getUpdatedAt()).isNull();
	}

	@Test
	void incorrectPasswordLeavesStoredRefreshUnchanged() throws Exception {
		Member member = newMember();
		String previous = tokens.issueRefreshToken(member.getMemberId());
		refresh.store(member.getMemberId(), previous);
		var result = login(member.getEmail(), PASSWORD.strip());
		assertThat(result.status()).isEqualTo(401);
		assertThat(result.body().get("code").asText()).isEqualTo("LOGIN_FAILED");
		assertThat(refresh.validate(previous).getMemberId()).isEqualTo(member.getMemberId());
	}

	@Test
	void failureAfterTokenWriteRollsBackToPreviousToken() throws Exception {
		Member member = newMember();
		long id = member.getMemberId();
		String previous = tokens.issueRefreshToken(id);
		refresh.store(id, previous);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new DataAccessResourceFailureException("simulated failure after token update");
		}).when(refresh).store(eq(id), anyString());

		var result = login(member.getEmail(), PASSWORD);
		assertThat(result.status()).isEqualTo(500);
		assertThat(result.body().size()).isEqualTo(3);
		assertThat(result.body().get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
		assertThat(refresh.validate(previous).getMemberId()).isEqualTo(id);
	}

	@Test
	void concurrentLoginsLeaveExactlyOneCurrentRefreshToken() throws Exception {
		Member member = newMember();
		int contenders = 4;
		var executor = Executors.newFixedThreadPool(contenders);
		var ready = new CountDownLatch(contenders);
		var start = new CountDownLatch(1);
		var results = new ArrayList<Future<LoginResult>>();
		try {
			for (int i = 0; i < contenders; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
					return login(member.getEmail(), PASSWORD);
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (var future : results) {
				var result = future.get(15, TimeUnit.SECONDS);
				assertThat(result.status()).isEqualTo(200);
				assertThat(tokens.validateAccessToken(result.body().at("/data/accessToken").asText()).memberId())
					.isEqualTo(member.getMemberId());
			}
			// 모든 HTTP 요청 완료 뒤 최종 저장값과 대조한다.
			long matchingTokens = 0;
			Member stored = members.findById(member.getMemberId()).orElseThrow();
			for (var future : results) {
				String value = future.get().body().at("/data/refreshToken").asText();
				String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				if (hash.equals(stored.getRefreshTokenHash())) {
					matchingTokens++;
				}
			}
			assertThat(matchingTokens).isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Member newMember() {
		Member member = members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.email(UUID.randomUUID() + "@example.com").password(encoder.encode(PASSWORD))
			.nickname("로그인 테스트").status("ACTIVE").createdAt(Instant.now()).build());
		memberIds.add(member.getMemberId());
		return member;
	}

	private LoginResult login(String email, String password) throws Exception {
		var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
			.andReturn().getResponse();
		return new LoginResult(response.getStatus(), mapper.readTree(response.getContentAsByteArray()));
	}

	private record LoginResult(int status, JsonNode body) {
	}
}
