package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.BindException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.dto.PasswordChangeRequest;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.PasswordChangeService;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeIntegrationTest {

	private static final String PATH = "/members/me/password";
	private static final String CURRENT = "  Old-private-password  ";
	private static final String NEXT = "  New-private-password  ";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository repository;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private RefreshTokenService refreshTokens;
	@Autowired private PasswordEncoder encoder;
	@Autowired private PasswordChangeService service;
	@Autowired private PlatformTransactionManager transactions;

	private final List<Long> memberIds = new ArrayList<>();

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@AfterEach
	void deleteOnlyMembersCreatedByThisTest() {
		repository.deleteAllById(memberIds);
	}

	@Test
	void changesPasswordRevokesAllExistingRefreshTokensAndKeepsAccessAndOtherMembers() throws Exception {
		long id = newMember(LoginType.LOCAL);
		long otherId = newMember(LoginType.LOCAL);
		String firstAccess = tokens.issueAccessToken(id);
		String secondAccess = tokens.issueAccessToken(id);
		String oldRefresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, oldRefresh);
		String currentRefresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, currentRefresh);
		String otherRefresh = tokens.issueRefreshToken(otherId);
		refreshTokens.store(otherId, otherRefresh);
		Member before = repository.findById(id).orElseThrow();
		Member otherBefore = repository.findById(otherId).orElseThrow();
		Instant startedAt = Instant.now();

		mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccess)
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
				"currentPassword", CURRENT, "newPassword", NEXT, "memberId", otherId))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다."));

		Member after = repository.findById(id).orElseThrow();
		assertThat(after.getPassword()).isNotEqualTo(before.getPassword()).isNotEqualTo(NEXT);
		assertThat(encoder.matches(NEXT, after.getPassword())).isTrue();
		assertThat(encoder.matches(CURRENT, after.getPassword())).isFalse();
		assertThat(encoder.matches(NEXT.strip(), after.getPassword())).isFalse();
		assertThat(after.getUpdatedAt()).isBetween(startedAt, Instant.now());
		assertThat(after.getRefreshTokenHash()).isNull();
		assertThat(after.getRefreshTokenExpiresAt()).isNull();
		assertThat(after).usingRecursiveComparison()
			.ignoringFields("password", "updatedAt", "refreshTokenHash", "refreshTokenExpiresAt").isEqualTo(before);
		assertThat(repository.findById(otherId).orElseThrow()).usingRecursiveComparison().isEqualTo(otherBefore);
		assertThat(refreshTokens.validate(otherRefresh).getMemberId()).isEqualTo(otherId);
		for (String refresh : List.of(oldRefresh, currentRefresh)) {
			postJson("/auth/refresh", Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		}
		for (String access : List.of(firstAccess, secondAccess)) {
			mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(true));
			// 보호 API까지 실제 필터를 통과하며 현재 비밀번호 불일치로만 거부한다.
			mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
					"currentPassword", CURRENT, "newPassword", "next-attempt"))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));
		}
		postJson("/auth/login", Map.of("email", before.getEmail(), "password", CURRENT))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
		postJson("/auth/login", Map.of("email", before.getEmail(), "password", NEXT))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isString())
			.andExpect(jsonPath("$.data.refreshToken").isString());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void allowsMissingOrExpiredStoredRefreshToken(boolean expired) throws Exception {
		long id = newMember(LoginType.LOCAL);
		if (expired) {
			jdbc.update("update member set refresh_token_hash = ?, refresh_token_expires_at = ? where member_id = ?",
				"f".repeat(64), java.sql.Timestamp.from(Instant.now().minusSeconds(60)), id);
		}
		change(id, CURRENT, NEXT).andExpect(status().isOk());
		Member after = repository.findById(id).orElseThrow();
		assertThat(encoder.matches(NEXT, after.getPassword())).isTrue();
		assertThat(after.getRefreshTokenHash()).isNull();
		assertThat(after.getRefreshTokenExpiresAt()).isNull();
	}

	@Test
	void validationMismatchAndSamePasswordFailuresPreserveEveryStoredField() throws Exception {
		long id = newMember(LoginType.LOCAL);
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);
		Member before = repository.findById(id).orElseThrow();
		for (String invalid : List.of("", " \t\r\n", "a".repeat(73), "가".repeat(25), "🎮".repeat(19))) {
			change(id, CURRENT, invalid).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			assertThat(repository.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
		}
		change(id, "wrong-private-password", NEXT).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));
		change(id, CURRENT, CURRENT).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
		assertThat(repository.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
		assertThat(refreshTokens.validate(refresh).getMemberId()).isEqualTo(id);
	}

	@Test
	void steamAccountCannotChangePasswordOrRevokeRefreshToken() throws Exception {
		long id = newMember(LoginType.STEAM);
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);
		Member before = repository.findById(id).orElseThrow();
		change(id, CURRENT, NEXT).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_NOT_SUPPORTED"));
		assertThat(repository.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
		assertThat(refreshTokens.validate(refresh).getMemberId()).isEqualTo(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WITHDRAWN", "INACTIVE", "UNKNOWN", "active"})
	void missingAndInactiveMembersCannotChangePassword(String state) throws Exception {
		long id = newMember(LoginType.LOCAL);
		if (state.equals("MISSING")) {
			repository.deleteById(id);
		} else {
			jdbc.update("update member set status = ? where member_id = ?", state, id);
		}
		var before = jdbc.queryForList("select * from member where member_id = ?", id);
		change(id, CURRENT, NEXT).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		assertThat(jdbc.queryForList("select * from member where member_id = ?", id)).isEqualTo(before);
	}

	@Test
	void failureAfterUpdateRollsBackPasswordTimestampAndRefreshTokenTogether() {
		long id = newMember(LoginType.LOCAL);
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);
		Member before = repository.findById(id).orElseThrow();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			try {
				service.changePassword(new MemberPrincipal(id), new PasswordChangeRequest(CURRENT, NEXT));
			} catch (BindException exception) {
				throw new AssertionError(exception);
			}
			assertThat(repository.findById(id).orElseThrow().getRefreshTokenHash()).isNull();
			throw new IllegalStateException("test rollback");
		})).isInstanceOf(IllegalStateException.class).hasMessage("test rollback");
		assertThat(repository.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
		assertThat(refreshTokens.validate(refresh).getMemberId()).isEqualTo(id);
	}

	@Test
	void concurrentChangesCannotBothValidateTheOldPassword() throws Exception {
		long id = newMember(LoginType.LOCAL);
		refreshTokens.store(id, tokens.issueRefreshToken(id));
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> concurrentChange(id, "first-new-password", ready, start));
			var second = executor.submit(() -> concurrentChange(id, "second-new-password", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int firstStatus = first.get(15, TimeUnit.SECONDS);
			int secondStatus = second.get(15, TimeUnit.SECONDS);
			assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 400);
			Member after = repository.findById(id).orElseThrow();
			assertThat(encoder.matches(firstStatus == 200 ? "first-new-password" : "second-new-password",
				after.getPassword())).isTrue();
			assertThat(after.getRefreshTokenHash()).isNull();
			assertThat(after.getRefreshTokenExpiresAt()).isNull();
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private int concurrentChange(long id, String next, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		var response = change(id, CURRENT, next).andReturn().getResponse();
		if (response.getStatus() == 400) {
			assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText())
				.isEqualTo("CURRENT_PASSWORD_MISMATCH");
		}
		return response.getStatus();
	}

	private ResultActions change(long id, String current, String next) throws Exception {
		return mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(id))
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
	}

	private ResultActions postJson(String path, Map<String, String> body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
	}

	private long newMember(LoginType type) {
		Member member = repository.saveAndFlush(Member.builder().loginType(type)
			.email(type == LoginType.LOCAL ? "password-change-" + UUID.randomUUID() + "@example.com" : null)
			.password(type == LoginType.LOCAL ? encoder.encode(CURRENT) : null)
			.steamId(type == LoginType.STEAM ? BigInteger.valueOf(System.nanoTime()) : null)
			.status("ACTIVE").nickname("password-change-test").createdAt(Instant.parse("2026-09-14T00:00:00Z"))
			.updatedAt(Instant.parse("2026-09-15T00:00:00Z")).build());
		memberIds.add(member.getMemberId());
		return member.getMemberId();
	}
}
