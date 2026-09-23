package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanDeleteRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.service.CaseSearchStorageService;
import com.ssafy.thispatch.domain.patch.service.PatchPlanDeleteService;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

// 테스트 트랜잭션 없이 실제 API의 커밋·롤백과 요청 간 잠금을 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatchPlanDeleteIntegrationTest {

	private static final String PATH = "/members/me/patch-plans/{planId}";
	private static final List<String> CHILD_TABLES = List.of("patch_plan_entity", "patch_plan_restatement",
		"patch_plan_genre", "patch_plan_confirmed_slot");

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private PatchPlanDeleteService service;
	@Autowired private CaseSearchStorageService searchStorage;
	@MockitoSpyBean private PatchPlanDeleteRepository repository;
	@MockitoBean private AiPatchClient ai;
	@MockitoSpyBean private PatchSearchRepository search;
	private long memberId;
	private long otherMemberId;
	private long gameId;
	private int genreId;

	@BeforeEach
	void prepare() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = member();
		otherMemberId = member();
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '삭제 테스트', now())", gameId);
		jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (?, '삭제 테스트 장르')", genreId);
	}

	@AfterEach
	void cleanupOwnFixtures() {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			jdbc.update("""
				DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (
					SELECT e.patch_plan_entity_id FROM patch_plan_entity e JOIN patch_plan p USING (patch_plan_id)
					WHERE p.member_id IN (?, ?))
				""", memberId, otherMemberId);
			for (String table : CHILD_TABLES) {
				jdbc.update("DELETE FROM " + table + " WHERE patch_plan_id IN ("
					+ "SELECT patch_plan_id FROM patch_plan WHERE member_id IN (?, ?))", memberId, otherMemberId);
			}
			jdbc.update("DELETE FROM patch_plan WHERE member_id IN (?, ?)", memberId, otherMemberId);
			jdbc.update("DELETE FROM my_game WHERE member_id IN (?, ?)", memberId, otherMemberId);
			jdbc.update("DELETE FROM game WHERE appid = ?", gameId);
			jdbc.update("DELETE FROM tag WHERE tag_id = ?", genreId);
			jdbc.update("DELETE FROM member WHERE member_id IN (?, ?)", memberId, otherMemberId);
		});
		verifyNoInteractions(ai);
		verifyNoMoreInteractions(search);
	}

	@Test
	void deletesAllChildrenAndOnlyTargetHistoryThenRejectsReadSearchAndRepeatedDelete() throws Exception {
		long target = plan(memberId, true, true);
		long sameText = plan(memberId, true, true);
		long other = plan(otherMemberId, true, true);
		var sameBefore = snapshot(sameText);
		var otherBefore = snapshot(other);
		var gameBefore = jdbc.queryForMap("SELECT * FROM game WHERE appid = ?", gameId);
		var memberBefore = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", memberId);
		var tagBefore = jdbc.queryForMap("SELECT * FROM tag WHERE tag_id = ?", genreId);
		jdbc.update("INSERT INTO my_game (member_id, appid, created_at) VALUES (?, ?, now())", memberId, gameId);

		remove(target).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

		assertThat(snapshot(target).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
		assertThat(snapshot(sameText)).isEqualTo(sameBefore);
		assertThat(snapshot(other)).isEqualTo(otherBefore);
		assertThat(jdbc.queryForMap("SELECT * FROM game WHERE appid = ?", gameId)).isEqualTo(gameBefore);
		assertThat(jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", memberId)).isEqualTo(memberBefore);
		assertThat(jdbc.queryForMap("SELECT * FROM tag WHERE tag_id = ?", genreId)).isEqualTo(tagBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM my_game WHERE member_id = ? AND appid = ?",
			Integer.class, memberId, gameId)).isEqualTo(1);
		mvc.perform(get("/members/me/patch-plans").header("Authorization", auth()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.page.totalCount").value(1))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].planId").value(sameText));
		assertNotFound(mvc.perform(get(PATH, target).header("Authorization", auth())));
		assertNotFound(remove(target));
		assertNotFound(mvc.perform(post("/games/{gameId}/case-searches", gameId)
			.header("Authorization", auth()).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"planId":%d,"genreIds":[],"confirmedSlots":[{"target":{"name":"대상","role":"ENEMY"},
				"attribute":"체력","changeType":"MODIFY","direction":"INCREASE","magnitude":"20%%","scope":null}]}
				""".formatted(target))));
		verify(search).findGame(gameId);
	}

	@Test
	void missingOtherMembersAndUncompletedPlansCannotBeDeleted() throws Exception {
		long other = plan(otherMemberId, true, true);
		long draft = plan(memberId, false, true);
		var otherBefore = snapshot(other);
		var draftBefore = snapshot(draft);
		for (long id : new long[] {Long.MAX_VALUE, other, draft}) assertNotFound(remove(id));
		assertThat(snapshot(other)).isEqualTo(otherBefore);
		assertThat(snapshot(draft)).isEqualTo(draftBefore);
	}

	@Test
	void completedPlanWithoutChildrenOrMyGameRegistrationCanBeDeleted() throws Exception {
		long target = plan(memberId, true, false);
		remove(target).andExpect(status().isOk());
		assertThat(snapshot(target).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
	}

	@Test
	void withdrawnMemberCannotDeleteHistory() throws Exception {
		long target = plan(memberId, true, true);
		var before = snapshot(target);
		jdbc.update("UPDATE member SET status = 'WITHDRAWN' WHERE member_id = ?", memberId);
		remove(target).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		assertThat(snapshot(target)).isEqualTo(before);
	}

	@Test
	void failureAfterDeleteStatementsRollsBackAllSixTables() throws Exception {
		long target = plan(memberId, true, true);
		var before = snapshot(target);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertThat(snapshot(target).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
			throw new DataAccessResourceFailureException("private-delete-failure");
		}).when(repository).deletePlanAndChildren(target);

		var result = remove(target).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-delete-failure");
		assertThat(snapshot(target)).isEqualTo(before);
	}

	@Test
	void concurrentDeletesHaveExactlyOneSuccess() throws Exception {
		long target = plan(memberId, true, true);
		var executor = Executors.newFixedThreadPool(2);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try {
			var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					await(start);
					return remove(target).andReturn().getResponse().getStatus();
				}));
			}
			await(ready);
			start.countDown();
			assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 404);
			assertThat(snapshot(target).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void deleteAndResearchStorageSerializeOnOriginalPlan(boolean deleteFirst) throws Exception {
		long target = plan(memberId, true, true);
		var executor = Executors.newFixedThreadPool(2);
		var firstStored = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var secondStarted = new CountDownLatch(1);
		var secondBackend = new AtomicInteger();
		try {
			var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
				if (deleteFirst) service.deletePlan(memberId, target);
				else searchStorage.save(memberId, gameId, target, List.of(genreId), List.of());
				firstStored.countDown();
				await(release);
			}));
			await(firstStored);
			var second = executor.submit(() -> {
				try {
					new TransactionTemplate(transactions).executeWithoutResult(tx -> {
						secondBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
						secondStarted.countDown();
						if (deleteFirst) searchStorage.save(memberId, gameId, target, List.of(genreId), List.of());
						else service.deletePlan(memberId, target);
					});
					return "OK";
				} catch (BusinessException exception) {
					return exception.getErrorCode().getCode();
				}
			});
			await(secondStarted);
			awaitDatabaseLock(secondBackend.get());
			release.countDown();
			first.get(15, TimeUnit.SECONDS);
			assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(deleteFirst ? "PATCH_PLAN_NOT_FOUND" : "OK");
			assertThat(snapshot(target).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
			var remaining = jdbc.queryForList("SELECT patch_plan_id FROM patch_plan WHERE member_id = ?", Long.class, memberId);
			assertThat(remaining).hasSize(deleteFirst ? 0 : 1);
			if (!deleteFirst) {
				var copy = snapshot(remaining.get(0));
				assertThat(copy.get("patch_plan_entity")).hasSize(2);
				assertThat(copy.get("patch_plan_slot")).hasSize(2);
				assertThat(copy.get("patch_plan_restatement")).hasSize(1);
				assertThat(copy.get("patch_plan_genre")).hasSize(1);
			}
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private long member() {
		return jdbc.queryForObject("INSERT INTO member (login_type, status, created_at) "
			+ "VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id", Long.class);
	}

	private long plan(long owner, boolean completed, boolean children) {
		long id = jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, structured_at, created_at)
			VALUES (?, ?, '같은 원문', now(), CASE WHEN ? THEN now() ELSE NULL END) RETURNING patch_plan_id
			""", Long.class, owner, gameId, completed);
		if (!children) return id;
		for (int order = 1; order <= 2; order++) {
			long entity = jdbc.queryForObject("""
				INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role)
				VALUES (?, ?, ?, 'ENEMY') RETURNING patch_plan_entity_id
				""", Long.class, id, order, "대상" + order);
			jdbc.update("""
				INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction)
				VALUES (?, ?, '체력', 'MODIFY', 'INCREASE')
				""", entity, order);
		}
		jdbc.update("INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, '해석', now())", id);
		if (completed) {
			jdbc.update("INSERT INTO patch_plan_genre (patch_plan_id, genre_id) VALUES (?, ?)", id, genreId);
			jdbc.update("""
				INSERT INTO patch_plan_confirmed_slot (patch_plan_id, slot_order, target_name, target_role,
					attribute, change_type, direction, created_at)
				VALUES (?, 1, '대상1', 'ENEMY', '체력', 'MODIFY', 'INCREASE', now())
				""", id);
		}
		return id;
	}

	private Map<String, List<Map<String, Object>>> snapshot(long planId) {
		var result = new LinkedHashMap<String, List<Map<String, Object>>>();
		result.put("patch_plan", jdbc.queryForList("SELECT * FROM patch_plan WHERE patch_plan_id = ?", planId));
		for (String table : CHILD_TABLES) {
			result.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE patch_plan_id = ? ORDER BY 1", planId));
		}
		result.put("patch_plan_slot", jdbc.queryForList("""
			SELECT s.* FROM patch_plan_slot s JOIN patch_plan_entity e USING (patch_plan_entity_id)
			WHERE e.patch_plan_id = ? ORDER BY s.patch_plan_slot_id
			""", planId));
		return result;
	}

	private String auth() { return "Bearer " + tokens.issueAccessToken(memberId); }

	private ResultActions remove(long planId) throws Exception {
		return mvc.perform(delete(PATH, planId).header("Authorization", auth()));
	}

	private void assertNotFound(ResultActions result) throws Exception {
		var response = result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PATCH_PLAN_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("기획안 내역을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(mapper.readTree(response.getResponse().getContentAsByteArray()).size()).isEqualTo(3);
	}

	private void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private void awaitDatabaseLock(int backendPid) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0",
				Boolean.class, backendPid))) return;
			Thread.sleep(10);
		}
		throw new AssertionError("후속 요청이 원본 기획안의 잠금을 기다려야 합니다.");
	}
}
