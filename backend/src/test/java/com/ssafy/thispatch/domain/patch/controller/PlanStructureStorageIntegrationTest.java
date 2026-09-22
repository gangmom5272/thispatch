package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.Change;
import com.ssafy.thispatch.client.ai.AiPatchContracts.PlanRequest;
import com.ssafy.thispatch.client.ai.AiPatchContracts.PlanResponse;
import com.ssafy.thispatch.domain.patch.repository.PlanStructureStorageRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

// 테스트 자체에는 트랜잭션을 걸지 않아 실제 서비스의 커밋·롤백과 AI 호출 경계를 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanStructureStorageIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private AiPatchClient ai;
	@MockitoSpyBean private PlanStructureStorageRepository repository;
	private long memberId;
	private long gameId;
	private int genreId;

	@BeforeEach
	void prepare() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = jdbc.queryForObject("""
			INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id
			""", Long.class);
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '구조화 저장 테스트', now())", gameId);
		jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (?, '테스트 장르')", genreId);
		jdbc.update("INSERT INTO game_tag (appid, tag_id, weight) VALUES (?, ?, 1)", gameId, genreId);
		respondWith(List.of());
	}

	@AfterEach
	void cleanup() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("""
				DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (
					SELECT e.patch_plan_entity_id FROM patch_plan_entity e
					JOIN patch_plan p ON p.patch_plan_id = e.patch_plan_id WHERE p.member_id = ?)
				""", memberId);
			for (String table : List.of("patch_plan_entity", "patch_plan_restatement", "patch_plan_genre", "patch_plan_confirmed_slot")) {
				jdbc.update("DELETE FROM " + table + " WHERE patch_plan_id IN (SELECT patch_plan_id FROM patch_plan WHERE member_id = ?)", memberId);
			}
			jdbc.update("DELETE FROM patch_plan WHERE member_id = ?", memberId);
			jdbc.update("DELETE FROM game_tag WHERE appid = ?", gameId);
			jdbc.update("DELETE FROM game WHERE appid = ?", gameId);
			jdbc.update("DELETE FROM tag WHERE tag_id = ?", genreId);
			jdbc.update("DELETE FROM member WHERE member_id = ?", memberId);
		});
	}

	@Test
	void savesOriginalValuesDeduplicatedEntitiesGlobalSlotOrderAndWarnings() throws Exception {
		respondWith(List.of(
			new Change(9, "modify", "increase", "enemy", "Axebot", "체력", "+20%", List.of("고통 4 이상", "보스전"), "체력 상향", "원문"),
			new Change(2, "remove", "none", "unknown", "Wraith", "등장", null, List.of(), "대상 확인 필요", "원문"),
			new Change(7, "modify", "decrease", "enemy", "Axebot", "공격력", "-10%", List.of(), "공격력 하향", "원문"),
			new Change(4, "add", "not_applicable", "enemy", "Wraith", "등장", "", List.of(""), "적 추가", "원문"),
			new Change(5, "fix", "not_applicable", "system", null, null, null, List.of(), "", "원문")));
		String rawText = "  적 체력을 바꾸고 대상을 추가합니다.\n원문의 공백도 보존합니다.  ";
		var data = data(request(rawText).andExpect(status().isOk()));
		long planId = data.path("planId").asLong();
		assertThat(planId).isPositive();
		assertThat(data.size()).isEqualTo(7);
		assertThat(data.path("rawText").asText()).isEqualTo(rawText);
		assertThat(data.path("genreIds")).isEqualTo(mapper.valueToTree(List.of(genreId)));
		assertThat(data.path("restatement").path("highlights").path("primaryRole").asText()).isEqualTo("MIXED");

		var plan = jdbc.queryForMap("SELECT member_id, appid, raw_text, structured_at, created_at FROM patch_plan WHERE patch_plan_id = ?", planId);
		assertThat(plan).containsEntry("member_id", memberId).containsEntry("appid", gameId)
			.containsEntry("raw_text", rawText).containsEntry("created_at", null);
		assertThat(plan.get("structured_at")).isNotNull();
		assertThat(jdbc.queryForObject("""
			SELECT p.structured_at = r.created_at FROM patch_plan p
			JOIN patch_plan_restatement r USING (patch_plan_id) WHERE p.patch_plan_id = ?
			""", Boolean.class, planId)).isTrue();
		var entities = jdbc.queryForList("SELECT * FROM patch_plan_entity WHERE patch_plan_id = ? ORDER BY entity_order", planId);
		assertThat(entities).hasSize(4);
		for (int index = 0; index < entities.size(); index++) {
			var entity = entities.get(index);
			var expected = data.path("entities").get(index);
			assertThat(entity).containsEntry("entity_order", index + 1)
				.containsEntry("name", expected.path("name").asText()).containsEntry("role", expected.path("role").asText());
			boolean unknown = index == 1 || index == 3;
			assertThat(entity).containsEntry("warning_code", unknown ? "UNKNOWN_ENTITY" : null)
				.containsEntry("warning_message", unknown ? "변경 대상을 확인하고 필요하면 슬롯을 수정해주세요." : null);
		}
		var slots = jdbc.queryForList("""
			SELECT s.*, e.name, e.role FROM patch_plan_slot s
			JOIN patch_plan_entity e USING (patch_plan_entity_id) WHERE e.patch_plan_id = ? ORDER BY s.slot_order
			""", planId);
		assertThat(slots).hasSize(5);
		for (int index = 0; index < slots.size(); index++) {
			var slot = slots.get(index);
			var expected = data.path("slots").get(index);
			assertThat(slot).containsEntry("slot_order", index + 1)
				.containsEntry("name", expected.path("targetName").asText()).containsEntry("role", expected.path("targetRole").asText())
				.containsEntry("attribute", expected.path("attribute").asText()).containsEntry("change_type", expected.path("changeType").asText())
				.containsEntry("direction", expected.path("direction").asText())
				.containsEntry("magnitude", expected.path("magnitude").asText(null)).containsEntry("scope", expected.path("scope").asText(null));
		}
		assertThat(slots.get(0).get("patch_plan_entity_id")).isEqualTo(slots.get(2).get("patch_plan_entity_id"));
		var restatement = jdbc.queryForMap("SELECT text, warning_code, warning_message FROM patch_plan_restatement WHERE patch_plan_id = ?", planId);
		assertThat(restatement).containsEntry("text", "체력 상향\n대상 확인 필요\n공격력 하향\n적 추가\n")
			.containsEntry("warning_code", null).containsEntry("warning_message", null);
		assertNoSearchData(planId);
		verify(ai).structure(new PlanRequest("", rawText));
	}

	@Test
	void emptyResultIsSavedWithEmptyRestatementAndHiddenFromHistory() throws Exception {
		long planId = data(request("변경할 내용이 없습니다").andExpect(status().isOk())).path("planId").asLong();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_entity WHERE patch_plan_id = ?", Long.class, planId)).isZero();
		assertThat(jdbc.queryForMap("SELECT text, warning_code, warning_message FROM patch_plan_restatement WHERE patch_plan_id = ?", planId))
			.containsEntry("text", "").containsEntry("warning_code", "NO_CHANGES")
			.containsEntry("warning_message", "변경점을 찾지 못했습니다. 기획안을 구체적으로 입력해주세요.");
		assertNoSearchData(planId);
		mvc.perform(get("/members/me/patch-plans").header("Authorization", authorization()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.page.totalCount").value(0));
		mvc.perform(get("/members/me/patch-plans/{planId}", planId).header("Authorization", authorization()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PATCH_PLAN_NOT_FOUND"));
	}

	@Test
	void nonemptyChangesMayHaveEmptyRestatementWithoutNoChangesWarning() throws Exception {
		respondWith(List.of(new Change(1, "fix", "unknown", "unknown", null, null, null, List.of(), "", "원문")));
		long planId = data(request("빈 대상 버그를 고칩니다").andExpect(status().isOk())).path("planId").asLong();
		assertThat(jdbc.queryForMap("SELECT text, warning_code, warning_message FROM patch_plan_restatement WHERE patch_plan_id = ?", planId))
			.containsEntry("text", "").containsEntry("warning_code", null).containsEntry("warning_message", null);
		assertThat(jdbc.queryForMap("SELECT name, role, warning_code FROM patch_plan_entity WHERE patch_plan_id = ?", planId))
			.containsEntry("name", "").containsEntry("role", "UNKNOWN").containsEntry("warning_code", "UNKNOWN_ENTITY");
	}

	@Test
	void repeatedStructureRequestsCreateSeparatePlans() throws Exception {
		long first = data(request("같은 원문을 재전송합니다").andExpect(status().isOk())).path("planId").asLong();
		long second = data(request("같은 원문을 재전송합니다").andExpect(status().isOk())).path("planId").asLong();
		assertThat(second).isPositive().isNotEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan WHERE member_id = ? AND created_at IS NULL", Long.class, memberId)).isEqualTo(2);
	}

	@Test
	void aiFailureDoesNotStoreAnyPlan() throws Exception {
		when(ai.structure(any())).thenThrow(new BusinessException(AiErrorCode.AI_UNAVAILABLE));
		request("AI가 실패할 기획안입니다").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist());
		verifyNoInteractions(repository);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan WHERE member_id = ?", Long.class, memberId)).isZero();
	}

	@Test
	void databaseFailureAfterAllFourWritesRollsBackEntirePlan() throws Exception {
		respondWith(List.of(new Change(1, "modify", "increase", "enemy", "Axebot", "체력", "+20%", List.of(), "체력 상향", "원문")));
		var failedPlan = new AtomicLong();
		var failedEntity = new AtomicLong();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			invocation.callRealMethod();
			long planId = invocation.getArgument(0);
			failedPlan.set(planId);
			failedEntity.set(jdbc.queryForObject("SELECT patch_plan_entity_id FROM patch_plan_entity WHERE patch_plan_id = ?", Long.class, planId));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_slot WHERE patch_plan_entity_id = ?", Long.class, failedEntity.get())).isEqualTo(1);
			// 4개 테이블에 실제 INSERT한 뒤 NOT NULL 제약 위반을 일으킨다.
			jdbc.update("UPDATE patch_plan_restatement SET text = NULL WHERE patch_plan_id = ?", planId);
			return null;
		}).when(repository).insertRestatement(anyLong(), anyString(), nullable(com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Warning.class), any(OffsetDateTime.class));

		request("트랜잭션 전체를 검증합니다").andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andExpect(jsonPath("$.responsedAt").isString());
		assertThat(failedPlan.get()).isPositive();
		for (String table : List.of("patch_plan", "patch_plan_entity", "patch_plan_restatement")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patch_plan_id = ?", Long.class, failedPlan.get())).isZero();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_slot WHERE patch_plan_entity_id = ?", Long.class, failedEntity.get())).isZero();
		assertNoSearchData(failedPlan.get());
	}

	private void respondWith(List<Change> changes) {
		when(ai.structure(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("AI 호출 중 트랜잭션 없음").isFalse();
			return new PlanResponse(changes, "qwen", "v1", 1);
		});
	}

	private ResultActions request(String text) throws Exception {
		return mvc.perform(post("/games/{gameId}/plan-structures", gameId).header("Authorization", authorization())
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("text", text, "memberId", Long.MAX_VALUE, "genreIds", List.of(-1)))));
	}

	private String authorization() {
		return "Bearer " + tokens.issueAccessToken(memberId);
	}

	private JsonNode data(ResultActions result) throws Exception {
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(1);
		return body.path("data");
	}

	private void assertNoSearchData(long planId) {
		for (String table : List.of("patch_plan_genre", "patch_plan_confirmed_slot")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patch_plan_id = ?", Long.class, planId)).isZero();
		}
	}
}
