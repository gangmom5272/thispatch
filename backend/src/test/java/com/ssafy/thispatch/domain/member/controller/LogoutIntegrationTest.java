package com.ssafy.thispatch.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LogoutIntegrationTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private MemberRepository members;
	@Autowired
	private RefreshTokenService refreshTokens;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JwtProperties properties;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@Test
	void logoutClearsBothColumnsAndRepeatSucceedsWithSameAccessToken() throws Exception {
		long id = newMember();
		String access = tokens.issueAccessToken(id);
		String refresh = tokens.issueRefreshToken(id);
		refreshTokens.store(id, refresh);

		logout(access, refresh).andExpect(status().isOk());
		Member revoked = members.findById(id).orElseThrow();
		assertThat(revoked.getRefreshTokenHash()).isNull();
		assertThat(revoked.getRefreshTokenExpiresAt()).isNull();
		assertThatThrownBy(() -> refreshTokens.validate(refresh)).isInstanceOf(TokenValidationException.class);

		logout(access, refresh).andExpect(status().isOk());
		assertThat(tokens.validateAccessToken(access).memberId()).isEqualTo(id);
		assertThat(members.findById(id).orElseThrow().getRefreshTokenHash()).isNull();
	}

	@Test
	void oldAndNeverStoredTokensDoNotClearNewLoginToken() throws Exception {
		long id = newMember();
		String old = tokens.issueRefreshToken(id);
		String current = tokens.issueRefreshToken(id);
		refreshTokens.store(id, old);
		refreshTokens.store(id, current);
		Member before = refreshTokens.validate(current);
		String access = tokens.issueAccessToken(id);

		logout(access, old).andExpect(status().isOk());
		logout(access, tokens.issueRefreshToken(id)).andExpect(status().isOk());

		Member after = refreshTokens.validate(current);
		assertThat(after.getRefreshTokenHash()).isEqualTo(before.getRefreshTokenHash());
		assertThat(after.getRefreshTokenExpiresAt()).isEqualTo(before.getRefreshTokenExpiresAt());
	}

	@Test
	void ownerMismatchPreservesBothMembersTokens() throws Exception {
		long owner = newMember();
		long other = newMember();
		String ownerToken = tokens.issueRefreshToken(owner);
		String otherToken = tokens.issueRefreshToken(other);
		refreshTokens.store(owner, ownerToken);
		refreshTokens.store(other, otherToken);

		logout(tokens.issueAccessToken(other), ownerToken)
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

		assertThat(refreshTokens.validate(ownerToken).getMemberId()).isEqualTo(owner);
		assertThat(refreshTokens.validate(otherToken).getMemberId()).isEqualTo(other);
	}

	@Test
	void expiredRefreshTokenPreservesStoredToken() throws Exception {
		long id = newMember();
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));
		String expired = past.issueRefreshToken(id);
		// 만료 전 저장된 토큰을 재현하고 검증 실패 후 두 저장 컬럼이 보존되는지 확인한다.
		new RefreshTokenService(members, past).store(id, expired);
		Member before = members.findById(id).orElseThrow();

		logout(tokens.issueAccessToken(id), expired)
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

		Member after = members.findById(id).orElseThrow();
		assertThat(after.getRefreshTokenHash()).isEqualTo(before.getRefreshTokenHash());
		assertThat(after.getRefreshTokenExpiresAt()).isEqualTo(before.getRefreshTokenExpiresAt());
	}

	private long newMember() {
		return members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.status("ACTIVE").createdAt(Instant.now()).build()).getMemberId();
	}

	private ResultActions logout(String access, String refresh) throws Exception {
		return mvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("refreshToken", refresh))));
	}
}
