package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.repository.MyGameRegistrationRepository;
import com.ssafy.thispatch.domain.game.service.MyGameRegistrationService;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyGameRegistrationIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private MyGameRegistrationService service;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoSpyBean private MyGameRegistrationRepository registrations;

	private final List<Long> memberIds = new ArrayList<>();
	private final List<Long> gameIds = new ArrayList<>();

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@AfterEach
	void cleanOnlyOwnFixtures() {
		memberIds.forEach(id -> {
			jdbc.update("delete from my_game where member_id = ?", id);
			jdbc.update("delete from member where member_id = ?", id);
		});
		gameIds.forEach(id -> jdbc.update("delete from game where appid = ?", id));
	}

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void registersForLocalAndSteamMembersAndPreservesOriginalTimestampOnDuplicate(LoginType type) throws Exception {
		long memberId = newMember(type);
		long gameId = newGame();
		Instant before = databaseNow();
		assertThat(register(memberId, gameId).getStatus()).isEqualTo(200);
		Instant createdAt = createdAt(memberId, gameId);
		assertThat(createdAt).isBetween(before, databaseNow());

		assertError(register(memberId, gameId), 409, "MY_GAME_ALREADY_REGISTERED");
		assertThat(count(memberId, gameId)).isEqualTo(1);
		assertThat(createdAt(memberId, gameId)).isEqualTo(createdAt);
	}

	@Test
	void differentMembersCanRegisterTheSameGameIndependently() throws Exception {
		long first = newMember(LoginType.LOCAL);
		long second = newMember(LoginType.STEAM);
		long gameId = newGame();
		assertThat(register(first, gameId).getStatus()).isEqualTo(200);
		assertThat(register(second, gameId).getStatus()).isEqualTo(200);
		assertThat(count(first, gameId)).isEqualTo(1);
		assertThat(count(second, gameId)).isEqualTo(1);
	}

	@Test
	void sameMemberCanRegisterDifferentGames() throws Exception {
		long memberId = newMember(LoginType.LOCAL);
		long firstGame = newGame();
		long secondGame = newGame();
		assertThat(register(memberId, firstGame).getStatus()).isEqualTo(200);
		assertThat(register(memberId, secondGame).getStatus()).isEqualTo(200);
		assertThat(count(memberId, firstGame)).isEqualTo(1);
		assertThat(count(memberId, secondGame)).isEqualTo(1);
	}

	@Test
	void absentGameReturns404AndDoesNotInsert() throws Exception {
		long memberId = newMember(LoginType.LOCAL);
		long gameId = newGame();
		jdbc.update("delete from game where appid = ?", gameId);
		assertError(register(memberId, gameId), 404, "GAME_NOT_FOUND");
		assertThat(count(memberId, gameId)).isZero();
	}

	@Test
	void withdrawnAndMissingMembersAreRejectedByRealAuthenticationFilter() throws Exception {
		long memberId = newMember(LoginType.LOCAL);
		long gameId = newGame();
		jdbc.update("update member set status = 'WITHDRAWN' where member_id = ?", memberId);
		assertError(register(memberId, gameId), 401, "UNAUTHORIZED");
		jdbc.update("delete from member where member_id = ?", memberId);
		assertError(register(memberId, gameId), 401, "UNAUTHORIZED");
		assertThat(count(memberId, gameId)).isZero();
	}

	@Test
	void simultaneousRegistrationsAfterGameLookupCommitExactlyOneRow() throws Exception {
		long memberId = newMember(LoginType.LOCAL);
		long gameId = newGame();
		int requestCount = 4;
		var checked = new CountDownLatch(requestCount);
		var release = new CountDownLatch(1);
		// 모든 요청이 게임 존재 확인을 통과한 뒤 INSERT를 동시에 시작하도록 경합을 고정한다.
		doAnswer(invocation -> {
			Object exists = invocation.callRealMethod();
			checked.countDown();
			assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
			return exists;
		}).when(registrations).lockExistingGame(gameId);
		var executor = Executors.newFixedThreadPool(requestCount);
		try {
			List<Future<MockHttpServletResponse>> pending = new ArrayList<>();
			for (int i = 0; i < requestCount; i++) {
				pending.add(executor.submit(() -> register(memberId, gameId)));
			}
			assertThat(checked.await(15, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (var future : pending) {
				var response = future.get(20, TimeUnit.SECONDS);
				statuses.add(response.getStatus());
				if (response.getStatus() == 409) {
					assertError(response, 409, "MY_GAME_ALREADY_REGISTERED");
				}
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409, 409);
			assertThat(count(memberId, gameId)).isEqualTo(1);
			assertThat(createdAt(memberId, gameId)).isNotNull();
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void registrationParticipatesInCallerTransactionAndRollsBackOnFailure() throws Exception {
		long memberId = newMember(LoginType.LOCAL);
		long gameId = newGame();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.register(new MemberPrincipal(memberId), gameId);
			throw new IllegalStateException("rollback-fixture");
		})).isInstanceOf(IllegalStateException.class).hasMessage("rollback-fixture");
		assertThat(count(memberId, gameId)).isZero();
		assertThat(register(memberId, gameId).getStatus()).isEqualTo(200);
	}

	private long newMember(LoginType type) {
		Member member = members.saveAndFlush(Member.builder().loginType(type).status("ACTIVE")
			.email(type == LoginType.LOCAL ? UUID.randomUUID() + "@example.com" : null)
			.nickname(type == LoginType.LOCAL ? "등록 테스트" : null)
			.createdAt(Instant.now()).build());
		memberIds.add(member.getMemberId());
		return member.getMemberId();
	}

	private long newGame() {
		long gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, Long.MAX_VALUE);
		jdbc.update("insert into game (appid, name, collected_at) values (?, ?, current_timestamp)",
			gameId, "my-game-register-" + UUID.randomUUID());
		gameIds.add(gameId);
		return gameId;
	}

	private int count(long memberId, long gameId) {
		return jdbc.queryForObject("select count(*) from my_game where member_id = ? and appid = ?",
			Integer.class, memberId, gameId);
	}

	private Instant databaseNow() {
		// created_at은 DB에서 생성하므로 테스트 JVM과 DB 호스트의 시계 차이를 배제한다.
		return jdbc.queryForObject("select clock_timestamp()", (rs, rowNum) -> rs.getTimestamp(1).toInstant());
	}

	private Instant createdAt(long memberId, long gameId) {
		return jdbc.queryForObject("select created_at from my_game where member_id = ? and appid = ?",
			(rs, rowNum) -> rs.getTimestamp("created_at").toInstant(), memberId, gameId);
	}

	private MockHttpServletResponse register(long memberId, long gameId) throws Exception {
		return mvc.perform(post("/games/{gameId}/my-game", gameId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueAccessToken(memberId)))
			.andReturn().getResponse();
	}

	private void assertError(MockHttpServletResponse response, int expectedStatus, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(expectedStatus);
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("code").asText()).isEqualTo(code);
		assertThat(body.path("message").asText()).isNotBlank();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		if (expectedStatus == 401) {
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
	}
}
