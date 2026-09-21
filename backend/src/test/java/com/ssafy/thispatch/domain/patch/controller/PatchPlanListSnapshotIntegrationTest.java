package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanListRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatchPlanListSnapshotIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@MockitoSpyBean private PatchPlanListRepository repository;

	@Test
	void concurrentCommittedHistoryDoesNotSplitCountAndItemsSnapshot() throws Exception {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		long member = jdbc.queryForObject("""
			INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id
			""", Long.class);
		long game = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		boolean gameCreated = false;
		var counted = new CountDownLatch(1);
		var proceed = new CountDownLatch(1);
		var interceptOnce = new AtomicBoolean(true);
		var executor = Executors.newSingleThreadExecutor();
		try {
			jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '변경 전 게임 이름', now())", game);
			gameCreated = true;
			jdbc.update("""
				INSERT INTO patch_plan (member_id, appid, raw_text, created_at) VALUES (?, ?, '기존 내역', now())
				""", member, game);
			doAnswer(invocation -> {
				Object count = invocation.callRealMethod();
				if (interceptOnce.compareAndSet(true, false)) {
					counted.countDown();
					assertThat(proceed.await(15, TimeUnit.SECONDS)).as("동시 저장 완료").isTrue();
				}
				return count;
			}).when(repository).count(member, null);
			String auth = "Bearer " + tokens.issueAccessToken(member);
			var future = executor.submit(() -> mvc.perform(get("/members/me/patch-plans").header("Authorization", auth))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
			assertThat(counted.await(15, TimeUnit.SECONDS)).as("개수 조회 완료").isTrue();
			jdbc.update("""
				INSERT INTO patch_plan (member_id, appid, raw_text, created_at) VALUES (?, ?, '새 내역', now())
				""", member, game);
			jdbc.update("UPDATE game SET name = '변경 후 게임 이름' WHERE appid = ?", game);
			proceed.countDown();
			var during = mapper.readTree(future.get(15, TimeUnit.SECONDS)).path("data");
			assertThat(during.path("page").path("totalCount").asLong()).isEqualTo(1);
			assertThat(during.path("items").size()).isEqualTo(1);
			assertThat(during.path("items").get(0).path("gameTitle").asText()).isEqualTo("변경 전 게임 이름");
			var afterResponse = mvc.perform(get("/members/me/patch-plans").header("Authorization", auth))
				.andExpect(status().isOk()).andReturn().getResponse();
			var after = mapper.readTree(afterResponse.getContentAsByteArray()).path("data");
			assertThat(after.path("page").path("totalCount").asLong()).isEqualTo(2);
			assertThat(after.path("items").size()).isEqualTo(2);
			assertThat(after.path("items").get(0).path("gameTitle").asText()).isEqualTo("변경 후 게임 이름");
		} finally {
			proceed.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
			reset(repository);
			jdbc.update("DELETE FROM patch_plan WHERE member_id = ?", member);
			if (gameCreated) jdbc.update("DELETE FROM game WHERE appid = ?", game);
			jdbc.update("DELETE FROM member WHERE member_id = ?", member);
		}
	}
}
