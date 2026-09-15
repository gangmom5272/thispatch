package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupServiceIntegrationTest {

	private static final String PASSWORD = " 가입 Password ";
	private static final String NICKNAME = " 같은 닉네임 😀 ";
	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@MockitoSpyBean
	private PasswordEncoder encoder;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private MemberRepository members;
	@MockitoSpyBean
	private JwtTokenProvider tokens;
	@MockitoSpyBean
	private RefreshTokenService refresh;

	private final List<String> emails = new ArrayList<>();

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@AfterEach
	void cleanOnlyOwnFixtures() {
		emails.forEach(email -> jdbc.update("delete from member where email = ?", email));
	}

	@Test
	void signupStoresLocalMemberAndTokensAndSupportsLoginRefreshAndSession() throws Exception {
		String email = newEmail();
		Instant before = Instant.now();
		var result = signup(" \t" + email.toUpperCase(Locale.ROOT) + "\n ", PASSWORD);
		assertThat(result.status()).isEqualTo(200);
		Member member = members.findByEmail(email).orElseThrow();
		long id = member.getMemberId();
		assertThat(member.getLoginType()).isEqualTo(LoginType.LOCAL);
		assertThat(member.getStatus()).isEqualTo("ACTIVE");
		assertThat(member.getSteamId()).isNull();
		assertThat(member.getNickname()).isEqualTo(NICKNAME);
		assertThat(member.getCreatedAt()).isBetween(before, Instant.now());
		assertThat(member.getUpdatedAt()).isNull();
		assertThat(member.getPassword()).hasSize(60).isNotEqualTo(PASSWORD);
		assertThat(encoder.matches(PASSWORD, member.getPassword())).isTrue();
		assertThat(encoder.matches(PASSWORD.strip(), member.getPassword())).isFalse();
		String access = result.body().at("/data/accessToken").asText();
		String refreshToken = result.body().at("/data/refreshToken").asText();
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(id);
		assertThat(refresh.validate(refreshToken).getMemberId()).isEqualTo(id);
		assertThat(member.getRefreshTokenHash()).matches("[0-9a-f]{64}").isNotEqualTo(refreshToken);
		assertThat(member.getRefreshTokenExpiresAt()).isEqualTo(tokens.validateRefreshToken(refreshToken).expiresAt());
		var renewed = postJson("/auth/refresh", Map.of("refreshToken", refreshToken));
		assertThat(renewed.status()).isEqualTo(200);
		assertThat(tokens.validateAccessToken(renewed.body().at("/data/accessToken").asText()).memberId()).isEqualTo(id);
		var session = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/session")
			.header("Authorization", "Bearer " + access)).andReturn().getResponse();
		assertThat(session.getStatus()).isEqualTo(200);
		JsonNode sessionBody = mapper.readTree(session.getContentAsByteArray());
		assertThat(sessionBody.at("/data/user/id").asLong()).isEqualTo(id);
		assertThat(sessionBody.at("/data/user/nickname").asText()).isEqualTo(NICKNAME);
		assertThat(postJson("/auth/login", Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"ACTIVE", "WITHDRAWN", "UNKNOWN", "STEAM"})
	void existingEmailIsRejectedWithoutChangingMemberOrRefresh(String state) throws Exception {
		String email = newEmail();
		Member member = members.saveAndFlush(Member.builder().loginType(state.equals("STEAM") ? LoginType.STEAM : LoginType.LOCAL)
			.email(email).password(encoder.encode(PASSWORD)).nickname(NICKNAME)
			.status(state.equals("STEAM") ? "ACTIVE" : state).createdAt(Instant.now()).build());
		String previous = tokens.issueRefreshToken(member.getMemberId());
		refresh.store(member.getMemberId(), previous);
		Map<String, Object> before = jdbc.queryForMap("select * from member where member_id = ?", member.getMemberId());
		var result = signup(" " + email.toUpperCase(Locale.ROOT) + " ", "another-password");
		assertThat(result.status()).isEqualTo(409);
		assertSafeError(result, "EMAIL_ALREADY_REGISTERED");
		assertThat(jdbc.queryForMap("select * from member where member_id = ?", member.getMemberId())).isEqualTo(before);
		assertThat(refresh.validate(previous).getMemberId()).isEqualTo(member.getMemberId());
	}

	@ParameterizedTest
	@ValueSource(strings = {"ISSUE", "STORE"})
	void failureAfterInsertRollsBackMemberAndAllowsRetry(String stage) throws Exception {
		String email = newEmail();
		if (stage.equals("ISSUE")) {
			doAnswer(invocation -> { throw new DataAccessResourceFailureException("private-issue-detail"); })
				.when(tokens).issueRefreshToken(anyLong());
		} else {
			doAnswer(invocation -> {
				invocation.callRealMethod();
				throw new DataAccessResourceFailureException("private-store-detail");
			}).when(refresh).store(anyLong(), anyString());
		}
		var result = signup(email, PASSWORD);
		assertThat(result.status()).isEqualTo(500);
		assertSafeError(result, "INTERNAL_SERVER_ERROR");
		assertThat(members.findByEmail(email)).isEmpty();
		org.mockito.Mockito.reset(tokens, refresh);
		assertThat(signup(email, PASSWORD).status()).isEqualTo(200);
	}

	@Test
	void simultaneousSignupAfterBothDuplicateChecksCreatesExactlyOneMember() throws Exception {
		String email = newEmail();
		var checked = new CountDownLatch(2);
		// 두 요청이 사전 중복 검사를 통과한 뒤 실제 BCrypt를 끝내고 함께 INSERT로 진행한다.
		doAnswer(invocation -> {
			Object hash = invocation.callRealMethod();
			checked.countDown();
			assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
			return hash;
		}).when(encoder).encode(anyString());
		var executor = Executors.newFixedThreadPool(2);
		var futures = new ArrayList<Future<SignupResult>>();
		try {
			futures.add(executor.submit(() -> signup(email, PASSWORD)));
			futures.add(executor.submit(() -> signup(" " + email.toUpperCase(Locale.ROOT) + " ", "other-password")));
			var results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
			assertThat(results).extracting(SignupResult::status).containsExactlyInAnyOrder(200, 409);
			assertThat(jdbc.queryForObject("select count(*) from member where email = ?", Integer.class, email)).isEqualTo(1);
			for (int i = 0; i < results.size(); i++) {
				var result = results.get(i);
				if (result.status() == 200) {
					Member stored = refresh.validate(result.body().at("/data/refreshToken").asText());
					assertThat(encoder.matches(i == 0 ? PASSWORD : "other-password", stored.getPassword())).isTrue();
					assertThat(tokens.validateAccessToken(result.body().at("/data/accessToken").asText()).memberId())
						.isEqualTo(stored.getMemberId());
				} else {
					assertSafeError(result, "EMAIL_ALREADY_REGISTERED");
				}
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void sameNicknameIsAllowedForDifferentEmails() throws Exception {
		assertThat(signup(newEmail(), PASSWORD).status()).isEqualTo(200);
		assertThat(signup(newEmail(), PASSWORD).status()).isEqualTo(200);
	}

	private String newEmail() {
		String email = UUID.randomUUID() + "@example.com";
		emails.add(email);
		return email;
	}

	private SignupResult signup(String email, String password) throws Exception {
		return postJson("/auth/signup", Map.of("email", email, "password", password, "nickname", NICKNAME));
	}

	private SignupResult postJson(String path, Map<String, String> input) throws Exception {
		var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(input))).andReturn().getResponse();
		return new SignupResult(response.getStatus(), mapper.readTree(response.getContentAsByteArray()));
	}

	private void assertSafeError(SignupResult result, String code) {
		assertThat(result.body().size()).isEqualTo(3);
		assertThat(result.body().get("code").asText()).isEqualTo(code);
		assertThat(result.body().toString()).doesNotContain(PASSWORD, "accessToken", "refreshToken", "errors",
			"private-issue-detail", "private-store-detail", "Exception", "success");
	}

	private record SignupResult(int status, JsonNode body) {
	}
}
