package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyGameListSnapshotIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;
	@MockitoSpyBean private GameListRepository repository;

	@Test
	void unregisterCommittedBetweenCountAndRowsDoesNotSplitResponseSnapshot() throws Exception {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		long memberId = members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.email(UUID.randomUUID() + "@example.com").status("ACTIVE").createdAt(Instant.now()).build()).getMemberId();
		long candidate;
		do {
			candidate = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid = ?", Integer.class, candidate) != 0);
		long gameId = candidate;
		var counted = new CountDownLatch(1);
		var proceed = new CountDownLatch(1);
		var interceptOnce = new AtomicBoolean(true);
		var executor = Executors.newSingleThreadExecutor();
		try {
			jdbc.update("insert into game (appid, name, store_positive_pct, collected_at) values (?, '스냅샷 게임', 10, now())", gameId);
			jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, gameId);
			doAnswer(invocation -> {
				Object count = invocation.callRealMethod();
				if (interceptOnce.compareAndSet(true, false)) {
					counted.countDown();
					assertThat(proceed.await(15, TimeUnit.SECONDS)).as("동시 등록 해제 완료").isTrue();
				}
				return count;
			}).when(repository).count(eq(memberId), any(), eq(GameListScope.MY));
			String authorization = "Bearer " + tokens.issueAccessToken(memberId);
			var future = executor.submit(() -> mvc.perform(get("/members/me/games")
				.header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray());
			assertThat(counted.await(15, TimeUnit.SECONDS)).as("개수 조회 완료").isTrue();
			assertThat(jdbc.update("delete from my_game where member_id = ? and appid = ?", memberId, gameId)).isEqualTo(1);
			proceed.countDown();
			var during = mapper.readTree(future.get(15, TimeUnit.SECONDS)).path("data");
			assertThat(during.path("page").path("totalCount").asLong()).isEqualTo(1);
			assertThat(during.path("items").size()).isEqualTo(1);
			assertThat(during.path("items").get(0).path("id").asLong()).isEqualTo(gameId);
			var afterResponse = mvc.perform(get("/members/me/games").header(HttpHeaders.AUTHORIZATION, authorization))
				.andExpect(status().isOk()).andReturn().getResponse();
			var after = mapper.readTree(afterResponse.getContentAsByteArray()).path("data");
			assertThat(after.path("page").path("totalCount").asLong()).isZero();
			assertThat(after.path("items")).isEmpty();
		} finally {
			proceed.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
			reset(repository);
			jdbc.update("delete from my_game where member_id = ?", memberId);
			jdbc.update("delete from game where appid = ?", gameId);
			jdbc.update("delete from member where member_id = ?", memberId);
		}
	}
}
