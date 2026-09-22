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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.domain.member.service.NicknameChangeService;
import com.ssafy.thispatch.domain.member.service.RefreshTokenService;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NicknameChangeIntegrationTest {

	private static final String PATH = "/members/me/nickname";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository repository;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private RefreshTokenService refreshTokens;
	@Autowired private NicknameChangeService service;
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

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void changesExistingNicknameAndSessionWhilePreservingTokensAndOtherMembers(LoginType type) throws Exception {
		long memberId = newMember(type, "ACTIVE", "before");
		long otherMemberId = newMember(type, "ACTIVE", "other");
		String accessToken = tokens.issueAccessToken(memberId);
		String refreshToken = tokens.issueRefreshToken(memberId);
		refreshTokens.store(memberId, refreshToken);
		Member before = repository.findById(memberId).orElseThrow();
		Member otherBefore = repository.findById(otherMemberId).orElseThrow();
		String nickname = "  한글 ! 🎮  ";

		mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("nickname", nickname, "memberId", otherMemberId))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(nickname))
			.andExpect(jsonPath("$.data.accessToken").doesNotExist())
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

		Member after = repository.findById(memberId).orElseThrow();
		assertThat(after.getNickname()).isEqualTo(nickname);
		assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());
		assertThat(after).usingRecursiveComparison().ignoringFields("nickname", "updatedAt").isEqualTo(before);
		assertThat(repository.findById(otherMemberId).orElseThrow()).usingRecursiveComparison().isEqualTo(otherBefore);
		assertThat(refreshTokens.validate(refreshToken).getMemberId()).isEqualTo(memberId);
		mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(true))
			.andExpect(jsonPath("$.data.user.nickname").value(nickname));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "same"})
	void acceptsUnsetAndIdenticalNicknamesWithoutInitialSettingConflict(String previousNickname) throws Exception {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", previousNickname);
		change(memberId, "same").andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("same"));
		assertThat(repository.findById(memberId).orElseThrow().getNickname()).isEqualTo("same");
	}

	@Test
	void allowsDuplicateTwentyCodePointNicknamesAndFurtherChanges() throws Exception {
		String nickname = "🎮".repeat(20);
		long first = newMember(LoginType.LOCAL, "ACTIVE", "first");
		long second = newMember(LoginType.STEAM, "ACTIVE", "second");
		change(first, nickname).andExpect(status().isOk());
		change(second, nickname).andExpect(status().isOk());
		assertThat(repository.findById(first).orElseThrow().getNickname()).isEqualTo(nickname);
		assertThat(repository.findById(second).orElseThrow().getNickname()).isEqualTo(nickname);
		change(first, "next").andExpect(status().isOk());
		assertThat(repository.findById(first).orElseThrow().getNickname()).isEqualTo("next");
	}

	@Test
	void initialSteamSettingStillRejectsOverwriteAfterProfileChange() throws Exception {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", null);
		change(memberId, "profile").andExpect(status().isOk());
		mvc.perform(post("/auth/steam/signup").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(memberId))
			.contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"initial\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_SET"));
		assertThat(repository.findById(memberId).orElseThrow().getNickname()).isEqualTo("profile");
	}

	@Test
	void invalidRequestsLeaveExistingMemberUnchanged() throws Exception {
		long memberId = newMember(LoginType.LOCAL, "ACTIVE", "original");
		Member before = repository.findById(memberId).orElseThrow();
		for (String nickname : List.of("", " \t\u3000", "🎮".repeat(21), "a\u0000b")) {
			change(memberId, nickname).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			assertThat(repository.findById(memberId).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
		}
	}

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void preservesLegacyLongNicknameUntilChangedToAtMostTwentyCodePoints(LoginType type) throws Exception {
		String legacyNickname = "🎮".repeat(50);
		long memberId = newMember(type, "ACTIVE", legacyNickname);
		Member before = repository.findById(memberId).orElseThrow();
		String accessToken = tokens.issueAccessToken(memberId);
		mvc.perform(get("/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(true))
			.andExpect(jsonPath("$.data.user.nickname").value(legacyNickname));

		change(memberId, legacyNickname).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("nickname"))
			.andExpect(jsonPath("$.errors[0].message").value("닉네임은 20자 이하로 입력해주세요."))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist());
		assertThat(repository.findById(memberId).orElseThrow()).usingRecursiveComparison().isEqualTo(before);

		String newNickname = "🎮".repeat(20);
		change(memberId, newNickname).andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(newNickname));
		assertThat(repository.findById(memberId).orElseThrow().getNickname()).isEqualTo(newNickname);
	}

	@ParameterizedTest
	@ValueSource(strings = {"WITHDRAWN", "INACTIVE", "UNKNOWN", "active"})
	void inactiveMembersAreRejectedByRealAuthenticationFilter(String memberStatus) throws Exception {
		long memberId = newMember(LoginType.LOCAL, memberStatus, "original");
		Member before = repository.findById(memberId).orElseThrow();
		change(memberId, "new").andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		assertThat(repository.findById(memberId).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
	}

	@Test
	void deletedMemberIsRejectedByRealAuthenticationFilter() throws Exception {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", "original");
		repository.deleteById(memberId);
		change(memberId, "new").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void conditionalUpdateRejectsStatusChangeAfterEarlierRead(boolean deleted) {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", "original");
		Member before = repository.findById(memberId).orElseThrow();
		TransactionTemplate independent = new TransactionTemplate(transactions);
		independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
			assertThat(repository.findById(memberId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
			independent.executeWithoutResult(inner -> {
				if (deleted) {
					repository.deleteById(memberId);
				} else {
					jdbc.update("update member set status = 'WITHDRAWN' where member_id = ?", memberId);
				}
			});
			service.changeNickname(new MemberPrincipal(memberId), "new");
		})).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(SecurityErrorCode.UNAUTHORIZED));

		if (deleted) {
			assertThat(repository.findById(memberId)).isEmpty();
		} else {
			Member after = repository.findById(memberId).orElseThrow();
			assertThat(after.getStatus()).isEqualTo("WITHDRAWN");
			assertThat(after).usingRecursiveComparison().ignoringFields("status").isEqualTo(before);
		}
	}

	@Test
	void failedTransactionRestoresNicknameAndTimestamp() {
		long memberId = newMember(LoginType.LOCAL, "ACTIVE", "original");
		Member before = repository.findById(memberId).orElseThrow();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
			service.changeNickname(new MemberPrincipal(memberId), "rolled-back");
			throw new IllegalStateException("test rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(repository.findById(memberId).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
	}

	private ResultActions change(long memberId, String nickname) throws Exception {
		return mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(memberId))
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("nickname", nickname))));
	}

	private long newMember(LoginType type, String memberStatus, String nickname) {
		Member member = repository.saveAndFlush(Member.builder().loginType(type)
			.steamId(type == LoginType.STEAM ? BigInteger.valueOf(System.nanoTime()) : null)
			.status(memberStatus).nickname(nickname).createdAt(Instant.parse("2026-09-14T00:00:00Z"))
			.updatedAt(Instant.parse("2026-09-15T00:00:00Z")).build());
		memberIds.add(member.getMemberId());
		return member.getMemberId();
	}
}
