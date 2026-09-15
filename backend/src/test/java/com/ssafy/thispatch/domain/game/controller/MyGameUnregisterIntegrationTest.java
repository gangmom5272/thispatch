package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.thispatch.domain.game.service.MyGameUnregisterService;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyGameUnregisterIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private MyGameUnregisterService service;
	@Autowired private PlatformTransactionManager transactions;

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

	@Test
	void deletesOnlyCurrentMembersTargetRelationAndRepeatedRequestReturns404() throws Exception {
		long memberId = member();
		long otherMember = member();
		long gameId = game();
		long otherGame = game();
		register(memberId, gameId);
		register(memberId, otherGame);
		register(otherMember, gameId);
		var gameBefore = jdbc.queryForMap("select * from game where appid = ?", gameId);
		var otherMemberBefore = relation(otherMember, gameId);
		var otherGameBefore = relation(memberId, otherGame);

		mvc.perform(delete("/games/{gameId}/my-game", gameId)
				.header(HttpHeaders.AUTHORIZATION, authorization(memberId)).param("memberId", Long.toString(otherMember))
				.contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":" + otherMember + "}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").doesNotExist());

		assertThat(count(memberId, gameId)).isZero();
		assertThat(relation(otherMember, gameId)).isEqualTo(otherMemberBefore);
		assertThat(relation(memberId, otherGame)).isEqualTo(otherGameBefore);
		assertThat(jdbc.queryForMap("select * from game where appid = ?", gameId)).isEqualTo(gameBefore);
		mvc.perform(delete("/games/{gameId}/my-game", gameId).header(HttpHeaders.AUTHORIZATION, authorization(memberId)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MY_GAME_NOT_REGISTERED"));
	}

	@Test
	void cannotDeleteAnotherMembersRegistration() throws Exception {
		long memberId = member();
		long otherMember = member();
		long gameId = game();
		register(otherMember, gameId);
		var before = relation(otherMember, gameId);

		mvc.perform(delete("/games/{gameId}/my-game", gameId).header(HttpHeaders.AUTHORIZATION, authorization(memberId)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MY_GAME_NOT_REGISTERED"));
		assertThat(relation(otherMember, gameId)).isEqualTo(before);
	}

	@Test
	void nonexistentGameReturnsDistinctGameError() throws Exception {
		long memberId = member();
		long absentGame = game();
		jdbc.update("delete from game where appid = ?", absentGame);

		mvc.perform(delete("/games/{gameId}/my-game", absentGame).header(HttpHeaders.AUTHORIZATION, authorization(memberId)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
	}

	@Test
	void withdrawnMemberCannotDeletePreservedRegistration() throws Exception {
		long memberId = member();
		long gameId = game();
		register(memberId, gameId);
		String access = authorization(memberId);
		jdbc.update("update member set status = 'WITHDRAWN' where member_id = ?", memberId);

		mvc.perform(delete("/games/{gameId}/my-game", gameId).header(HttpHeaders.AUTHORIZATION, access))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		assertThat(count(memberId, gameId)).isEqualTo(1);
	}

	@Test
	void concurrentUnregisterRequestsHaveExactlyOneSuccess() throws Exception {
		long memberId = member();
		long gameId = game();
		register(memberId, gameId);
		String access = authorization(memberId);
		var executor = Executors.newFixedThreadPool(2);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try {
			var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return mvc.perform(delete("/games/{gameId}/my-game", gameId)
						.header(HttpHeaders.AUTHORIZATION, access)).andReturn().getResponse().getStatus();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 404);
			assertThat(count(memberId, gameId)).isZero();
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void relationDeletionParticipatesInServiceTransaction() {
		long memberId = member();
		long gameId = game();
		register(memberId, gameId);
		var before = relation(memberId, gameId);

		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.unregister(new MemberPrincipal(memberId), gameId);
			assertThat(count(memberId, gameId)).isZero();
			transaction.setRollbackOnly();
		});
		assertThat(relation(memberId, gameId)).isEqualTo(before);
	}

	private long member() {
		Long id = jdbc.queryForObject(
			"insert into member (login_type, status, created_at) values ('LOCAL', 'ACTIVE', now()) returning member_id",
			Long.class);
		memberIds.add(id);
		return id;
	}

	private long game() {
		long id = 3_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000_000L);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'unregister fixture', now())", id);
		gameIds.add(id);
		return id;
	}

	private void register(long memberId, long gameId) {
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, gameId);
	}

	private int count(long memberId, long gameId) {
		return jdbc.queryForObject("select count(*) from my_game where member_id = ? and appid = ?",
			Integer.class, memberId, gameId);
	}

	private java.util.Map<String, Object> relation(long memberId, long gameId) {
		return jdbc.queryForMap("select * from my_game where member_id = ? and appid = ?", memberId, gameId);
	}

	private String authorization(long memberId) {
		return "Bearer " + tokens.issueAccessToken(memberId);
	}
}
