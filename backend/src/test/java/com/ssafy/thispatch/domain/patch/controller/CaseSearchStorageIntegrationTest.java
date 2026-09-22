package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.*;
import com.ssafy.thispatch.domain.patch.repository.CaseSearchStorageRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.*;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

// 실제 HTTP·인증·저장 트랜잭션을 검증한다. 테스트에 트랜잭션을 걸지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseSearchStorageIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private AiPatchClient ai;
	@MockitoSpyBean private PatchSearchRepository search;
	@MockitoSpyBean private CaseSearchStorageRepository storage;
	private long memberId;
	private long otherMemberId;
	private long gameId;
	private long planId;
	private int genreId;
	private final List<ConfirmedSlot> slots = List.of(
		new ConfirmedSlot(new Target("수정된 대상", TargetRole.PLAYER), "체력", ChangeType.MODIFY, Direction.DECREASE, "-10%", "어려움"),
		new ConfirmedSlot(new Target("새 대상", TargetRole.ITEM), "아이템", ChangeType.ADD, Direction.NOT_APPLICABLE, null, null),
		new ConfirmedSlot(new Target("", TargetRole.UNKNOWN), "", ChangeType.FIX, Direction.UNKNOWN, "", ""));

	@BeforeEach
	void prepare() throws Exception {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = insertMember();
		otherMemberId = insertMember();
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '검색 저장 테스트', now()), (?, '다른 게임', now())", gameId, gameId + 1);
		jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (?, '선택 장르')", genreId);
		when(ai.structure(any())).thenReturn(new PlanResponse(List.of(
			new Change(1, "modify", "increase", "enemy", "Axebot", "체력", "+20%", List.of("보스전"), "체력 상향", "원문"),
			new Change(2, "remove", "none", "unknown", "Wraith", "등장", null, List.of(), "미확인 대상", "원문"),
			new Change(3, "modify", "decrease", "enemy", "Axebot", "공격력", "-10%", List.of(), "공격 하향", "원문")), "qwen", "v1", 1));
		var response = mvc.perform(post("/games/{gameId}/plan-structures", gameId)
			.header("Authorization", authorization(memberId)).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("text", "  최초 원문과 공백을 보존합니다.\n기획안  "))))
			.andExpect(status().isOk()).andReturn().getResponse();
		planId = mapper.readTree(response.getContentAsByteArray()).path("data").path("planId").asLong();
		clearInvocations(ai);
		when(ai.embed(any())).thenAnswer(invocation -> {
			assertNoTransaction();
			return new EmbeddingResponse(List.of(List.of(1.0)), 512, "test-model");
		});
		doReturn(List.of()).when(search).search(anyList(), anyString(), anyList(), anyList());
	}

	@AfterEach
	void cleanup() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("""
				DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (
					SELECT e.patch_plan_entity_id FROM patch_plan_entity e
					JOIN patch_plan p USING (patch_plan_id) WHERE p.member_id = ?)
				""", memberId);
			for (String table : List.of("patch_plan_entity", "patch_plan_restatement", "patch_plan_genre", "patch_plan_confirmed_slot")) {
				jdbc.update("DELETE FROM " + table + " WHERE patch_plan_id IN (SELECT patch_plan_id FROM patch_plan WHERE member_id = ?)", memberId);
			}
			jdbc.update("DELETE FROM patch_plan WHERE member_id = ?", memberId);
			jdbc.update("DELETE FROM tag WHERE tag_id = ?", genreId);
			jdbc.update("DELETE FROM game WHERE appid IN (?, ?)", gameId, gameId + 1);
			jdbc.update("DELETE FROM member WHERE member_id IN (?, ?)", memberId, otherMemberId);
		});
	}

	@Test
	void firstSearchPreservesStructureAndStoresEditedSlotsWithDeduplicatedGenres() throws Exception {
		var original = structure(planId);
		var planBefore = plan(planId);
		request(planId, List.of(genreId, genreId), slots).andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("201")).andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(0)).andExpect(jsonPath("$.data.groups.length()").value(3))
			.andExpect(jsonPath("$.data.genreIds.length()").value(1)).andExpect(jsonPath("$.data.planId").doesNotExist());
		assertThat(planIds()).containsExactly(planId);
		assertThat(structure(planId)).isEqualTo(original);
		assertThat(plan(planId)).containsEntry("structured_at", planBefore.get("structured_at"))
			.containsEntry("raw_text", planBefore.get("raw_text"));
		assertConfirmed(planId, List.of(genreId), slots);
		mvc.perform(get("/members/me/patch-plans/{planId}", planId).header("Authorization", authorization(memberId)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedSlots[0].target.name").value("수정된 대상"))
			.andExpect(jsonPath("$.data.restatement.text").value("체력 상향\n미확인 대상\n공격 하향"));
		mvc.perform(get("/members/me/patch-plans").header("Authorization", authorization(memberId)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.page.totalCount").value(1));
	}

	@Test
	void researchAndIdenticalRetriesCopyOriginalStructureIntoSeparateHistories() throws Exception {
		request(planId, List.of(genreId), slots).andExpect(status().isCreated());
		var originalPlan = plan(planId);
		var originalStructure = structure(planId);
		var originalEntities = entityIds(planId);
		var edited = List.of(slots.get(2), slots.get(0));
		for (int retry = 0; retry < 2; retry++) {
			request(planId, List.of(), edited).andExpect(status().isCreated());
		}
		assertThat(planIds()).hasSize(3);
		for (long id : planIds()) {
			assertThat(structure(id)).isEqualTo(originalStructure);
			assertThat(plan(id)).containsEntry("structured_at", originalPlan.get("structured_at"))
				.containsEntry("raw_text", originalPlan.get("raw_text"));
			if (id != planId) {
				assertThat(entityIds(id)).doesNotContainAnyElementsOf(originalEntities);
				assertConfirmed(id, List.of(), edited);
			}
		}
		assertThat(plan(planId)).isEqualTo(originalPlan);
		assertConfirmed(planId, List.of(genreId), slots);
		verify(ai, never()).structure(any());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void legacyResearchPreservesNullStructuredTimeAndOptionalRestatement(boolean hasRestatement) throws Exception {
		jdbc.update("UPDATE patch_plan SET created_at = '2026-01-01T00:00:00Z', structured_at = NULL WHERE patch_plan_id = ?", planId);
		if (!hasRestatement) jdbc.update("DELETE FROM patch_plan_restatement WHERE patch_plan_id = ?", planId);
		var original = structure(planId);
		request(planId, List.of(), slots).andExpect(status().isCreated());
		long copyId = planIds().get(1);
		assertThat(plan(copyId)).containsEntry("structured_at", null);
		assertThat(structure(copyId)).isEqualTo(original);
		assertConfirmed(copyId, List.of(), slots);
	}

	@Test
	void emptyInitialStructureAndNoChangesWarningCanBeConfirmedAndCopied() throws Exception {
		jdbc.update("DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (SELECT patch_plan_entity_id FROM patch_plan_entity WHERE patch_plan_id = ?)", planId);
		jdbc.update("DELETE FROM patch_plan_entity WHERE patch_plan_id = ?", planId);
		jdbc.update("UPDATE patch_plan_restatement SET text = '', warning_code = 'NO_CHANGES', warning_message = '변경점 없음' WHERE patch_plan_id = ?", planId);
		var original = structure(planId);
		request(planId, List.of(), slots).andExpect(status().isCreated());
		request(planId, List.of(), slots).andExpect(status().isCreated());
		for (long id : planIds()) {
			assertThat(structure(id)).isEqualTo(original);
			assertConfirmed(id, List.of(), slots);
		}
	}

	@Test
	void invalidOwnershipGameOrGenreIsRejectedBeforeAiOrWrites() throws Exception {
		assertError(request(Long.MAX_VALUE, List.of(), slots), 404, "PATCH_PLAN_NOT_FOUND");
		assertError(request(otherMemberId, gameId, planId, List.of(), slots), 404, "PATCH_PLAN_NOT_FOUND");
		assertError(request(memberId, gameId + 1, planId, List.of(), slots), 400, "INVALID_REQUEST");
		assertError(request(planId, List.of(Integer.MAX_VALUE), slots), 400, "INVALID_REQUEST");
		verifyNoInteractions(ai);
		verify(storage, never()).lockOwnedPlan(anyLong(), anyLong());
		assertPending();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void comparisonFailurePreservesPendingOrCompletedHistory(boolean completed) throws Exception {
		if (completed) request(planId, List.of(genreId), slots).andExpect(status().isCreated());
		var before = plan(planId);
		var original = structure(planId);
		nonemptySearch();
		when(ai.compare(any())).thenAnswer(invocation -> {
			assertNoTransaction();
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		});
		assertError(request(planId, List.of(), List.of(slots.get(1))), 503, "AI_UNAVAILABLE");
		assertThat(planIds()).containsExactly(planId);
		assertThat(plan(planId)).isEqualTo(before);
		assertThat(structure(planId)).isEqualTo(original);
		if (completed) assertConfirmed(planId, List.of(genreId), slots);
		else assertPending();
	}

	@Test
	void nonemptySearchCallsAllAiOutsideTransactionAndThenCommits() throws Exception {
		nonemptySearch();
		request(planId, List.of(), slots).andExpect(status().isCreated()).andExpect(jsonPath("$.data.totalCount").value(1));
		assertConfirmed(planId, List.of(), slots);
		var order = inOrder(ai, storage);
		order.verify(ai).embed(any());
		order.verify(ai).cards(any());
		order.verify(ai).compare(any());
		order.verify(storage).lockOwnedPlan(memberId, planId);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void storageFailureRollsBackConfirmedInputAndAnyCopiedStructure(boolean completed) throws Exception {
		if (completed) request(planId, List.of(), slots).andExpect(status().isCreated());
		var before = plan(planId);
		var original = structure(planId);
		var failedId = new AtomicLong();
		var failedEntity = new AtomicLong();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			invocation.callRealMethod();
			long id = invocation.getArgument(0);
			failedId.set(id);
			failedEntity.set(entityIds(id).get(0));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_genre WHERE patch_plan_id = ?", Integer.class, id)).isEqualTo(1);
			// 실제 INSERT 이후 제약 위반을 일으켜 신규 부모·하위 행까지 롤백되는지 확인한다.
			jdbc.update("UPDATE patch_plan_confirmed_slot SET target_name = NULL WHERE patch_plan_id = ?", id);
			return null;
		}).when(storage).insertConfirmedSlot(anyLong(), eq(2), any(), any(OffsetDateTime.class));
		assertError(request(planId, List.of(genreId), slots), 500, "INTERNAL_SERVER_ERROR");
		assertThat(planIds()).containsExactly(planId);
		assertThat(plan(planId)).isEqualTo(before);
		assertThat(structure(planId)).isEqualTo(original);
		if (completed) {
			assertConfirmed(planId, List.of(), slots);
			assertThat(failedId.get()).isNotEqualTo(planId);
			for (String table : List.of("patch_plan", "patch_plan_entity", "patch_plan_restatement", "patch_plan_genre", "patch_plan_confirmed_slot")) {
				assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patch_plan_id = ?", Integer.class, failedId.get())).isZero();
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_slot WHERE patch_plan_entity_id = ?", Integer.class, failedEntity.get())).isZero();
		} else assertPending();
	}

	@Test
	void failureAfterFirstCompletionUpdateAlsoRollsBackTimestamp() throws Exception {
		doAnswer(invocation -> {
			invocation.callRealMethod();
			jdbc.update("UPDATE patch_plan SET raw_text = NULL WHERE patch_plan_id = ?", planId);
			return null;
		}).when(storage).completePlan(eq(planId), any());
		assertError(request(planId, List.of(genreId), slots), 500, "INTERNAL_SERVER_ERROR");
		assertPending();
	}

	@Test
	void ownershipAndGameAreRecheckedAfterAiBeforeSaving() throws Exception {
		when(ai.embed(any())).thenAnswer(invocation -> {
			jdbc.update("UPDATE patch_plan SET appid = ? WHERE patch_plan_id = ?", gameId + 1, planId);
			return new EmbeddingResponse(List.of(List.of(1.0)), 512, "test-model");
		});
		assertError(request(planId, List.of(), slots), 400, "INVALID_REQUEST");
		assertPending();
		jdbc.update("UPDATE patch_plan SET appid = ? WHERE patch_plan_id = ?", gameId, planId);
		doAnswer(invocation -> {
			jdbc.update("DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (SELECT patch_plan_entity_id FROM patch_plan_entity WHERE patch_plan_id = ?)", planId);
			jdbc.update("DELETE FROM patch_plan_entity WHERE patch_plan_id = ?", planId);
			jdbc.update("DELETE FROM patch_plan_restatement WHERE patch_plan_id = ?", planId);
			jdbc.update("DELETE FROM patch_plan WHERE patch_plan_id = ?", planId);
			return new EmbeddingResponse(List.of(List.of(1.0)), 512, "test-model");
		}).when(ai).embed(any());
		assertError(request(planId, List.of(), slots), 404, "PATCH_PLAN_NOT_FOUND");
		assertThat(planIds()).isEmpty();
	}

	@Test
	void concurrentFirstSearchesCommitTwoSeparateSlotSets() throws Exception {
		var bothInAi = new CyclicBarrier(2);
		when(ai.embed(any())).thenAnswer(invocation -> {
			assertNoTransaction();
			bothInAi.await(10, TimeUnit.SECONDS);
			return new EmbeddingResponse(List.of(List.of(1.0)), 512, "test-model");
		});
		var firstLocked = new CountDownLatch(1);
		var secondAttempted = new CountDownLatch(1);
		var attempts = new java.util.concurrent.atomic.AtomicInteger();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 1) {
				var result = invocation.callRealMethod();
				firstLocked.countDown();
				assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
				return result;
			}
			assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
			secondAttempted.countDown();
			return invocation.callRealMethod();
		}).when(storage).lockOwnedPlan(memberId, planId);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> request(planId, List.of(genreId), List.of(slots.get(0))).andExpect(status().isCreated()));
			var second = executor.submit(() -> request(planId, List.of(), List.of(slots.get(1))).andExpect(status().isCreated()));
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(planIds()).hasSize(2);
		assertThat(structure(planIds().get(1))).isEqualTo(structure(planId));
		var names = jdbc.queryForList("""
			SELECT s.target_name FROM patch_plan_confirmed_slot s JOIN patch_plan p USING (patch_plan_id)
			WHERE p.member_id = ? ORDER BY s.target_name
			""", String.class, memberId);
		assertThat(names).containsExactlyInAnyOrder("수정된 대상", "새 대상");
		for (long id : planIds()) {
			String name = jdbc.queryForObject("SELECT target_name FROM patch_plan_confirmed_slot WHERE patch_plan_id = ?", String.class, id);
			boolean first = name.equals("수정된 대상");
			assertConfirmed(id, first ? List.of(genreId) : List.of(), List.of(slots.get(first ? 0 : 1)));
		}
	}

	private void nonemptySearch() {
		var candidate = new Candidate(new PatchData("fixture", gameId, "게임", null, "패치", Instant.parse("2026-01-01T00:00:00Z"),
			10, new BigDecimal("70"), new BigDecimal("75"), new BigDecimal("5"), null, null), .8, List.of(),
			List.of(new CaseChange("modify", "increase", "enemy", "체력 상향")), false);
		doReturn(List.of(candidate)).when(search).search(anyList(), anyString(), anyList(), anyList());
		when(ai.cards(any())).thenAnswer(invocation -> {
			assertNoTransaction();
			return new CardsResponse(List.of(new Card("fixture", "공통점", "차이점")), List.of(), null);
		});
		when(ai.compare(any())).thenAnswer(invocation -> {
			assertNoTransaction();
			return new CompareResponse("fixture", List.of(), List.of(), false, 0);
		});
	}

	private long insertMember() {
		return jdbc.queryForObject("INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id", Long.class);
	}

	private ResultActions request(long id, List<Integer> genres, List<ConfirmedSlot> confirmed) throws Exception {
		return request(memberId, gameId, id, genres, confirmed);
	}

	private ResultActions request(long member, long game, long id, List<Integer> genres, List<ConfirmedSlot> confirmed) throws Exception {
		return mvc.perform(post("/games/{gameId}/case-searches", game).header("Authorization", authorization(member))
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new CaseSearchRequest(id, confirmed, genres, null))));
	}

	private String authorization(long member) {
		return "Bearer " + tokens.issueAccessToken(member);
	}

	private List<Long> planIds() {
		return jdbc.queryForList("SELECT patch_plan_id FROM patch_plan WHERE member_id = ? ORDER BY patch_plan_id", Long.class, memberId);
	}

	private List<Long> entityIds(long id) {
		return jdbc.queryForList("SELECT patch_plan_entity_id FROM patch_plan_entity WHERE patch_plan_id = ? ORDER BY entity_order", Long.class, id);
	}

	private Map<String, Object> plan(long id) {
		return jdbc.queryForMap("SELECT * FROM patch_plan WHERE patch_plan_id = ?", id);
	}

	private Object structure(long id) {
		return List.of(jdbc.queryForList("SELECT entity_order, name, role, warning_code, warning_message FROM patch_plan_entity WHERE patch_plan_id = ? ORDER BY entity_order", id),
			jdbc.queryForList("""
				SELECT e.entity_order, s.slot_order, s.attribute, s.change_type, s.direction, s.magnitude, s.scope
				FROM patch_plan_slot s JOIN patch_plan_entity e USING (patch_plan_entity_id)
				WHERE e.patch_plan_id = ? ORDER BY s.slot_order
				""", id),
			jdbc.queryForList("SELECT text, warning_code, warning_message, created_at FROM patch_plan_restatement WHERE patch_plan_id = ?", id));
	}

	private void assertConfirmed(long id, List<Integer> genres, List<ConfirmedSlot> confirmed) throws Exception {
		assertThat(plan(id).get("created_at")).isNotNull();
		assertThat(jdbc.queryForList("SELECT genre_id FROM patch_plan_genre WHERE patch_plan_id = ? ORDER BY genre_id", Integer.class, id))
			.containsExactlyElementsOf(genres);
		var response = mvc.perform(get("/members/me/patch-plans/{planId}", id).header("Authorization", authorization(memberId)))
			.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(mapper.readTree(response.getContentAsByteArray()).path("data").path("confirmedSlots"))
			.isEqualTo(mapper.valueToTree(confirmed));
		assertThat(jdbc.queryForList("SELECT slot_order FROM patch_plan_confirmed_slot WHERE patch_plan_id = ? ORDER BY slot_order", Integer.class, id))
			.containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, confirmed.size()).boxed().toList());
		assertThat(jdbc.queryForObject("""
			SELECT bool_and(s.created_at = p.created_at) FROM patch_plan_confirmed_slot s
			JOIN patch_plan p USING (patch_plan_id) WHERE p.patch_plan_id = ?
			""", Boolean.class, id)).isTrue();
	}

	private void assertPending() {
		assertThat(plan(planId)).containsEntry("created_at", null);
		for (String table : List.of("patch_plan_genre", "patch_plan_confirmed_slot")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patch_plan_id = ?", Integer.class, planId)).isZero();
		}
	}

	private void assertError(ResultActions result, int status, String code) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.responsedAt").isString()).andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist()).andExpect(jsonPath("$.errors").doesNotExist());
		assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsByteArray()).size()).isEqualTo(3);
	}

	private void assertNoTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("외부 AI 호출 중 트랜잭션 없음").isFalse();
	}
}
