package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PatchPlanDetailIntegrationTest {

	private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-21T05:32:01.123456Z");

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private AiPatchClient ai;
	@MockitoBean private PatchSearchRepository searchRepository;
	private long memberId;
	private long otherMemberId;
	private long gameId;

	@BeforeEach
	void prepareTestData() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = member();
		otherMemberId = member();
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '저장 당시 게임 이름', now())", gameId);
	}

	@AfterEach
	void detailDoesNotStructureRestateOrSearch() {
		verifyNoInteractions(ai, searchRepository);
	}

	@Test
	void returnsFullSavedTextGenresAndOrderedFinalSlotsWithCurrentGameName() throws Exception {
		String raw = " \n" + "원문😀".repeat(300) + "\r\n 끝 ";
		String restatement = "  저장된 해석\r\n줄바꿈과 공백 그대로 😀  ";
		long planId = plan(memberId, raw);
		restatement(planId, restatement);
		long otherPlan = plan(otherMemberId, "타인 원문");
		restatement(otherPlan, "타인 해석");
		confirmed(otherPlan, 1, "타인 슬롯", "PLAYER", "체력", "MODIFY", "DECREASE", "-1", "전체");
		confirmed(planId, 3, "Wraith", "UNKNOWN", "등장 빈도", "MODIFY", "UNKNOWN", null, null);
		confirmed(planId, 1, "Axebot", "ENEMY", "체력", "MODIFY", "INCREASE", "20%", "고통 4 이상");
		confirmed(planId, 2, "", "SYSTEM", "", "ADD", "NOT_APPLICABLE", "", "");
		long entity = jdbc.queryForObject("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role, warning_code, warning_message)
			VALUES (?, 1, '최초 대상', 'UNKNOWN', 'UNKNOWN_ENTITY', '최초 경고') RETURNING patch_plan_entity_id
			""", Long.class, planId);
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction)
			VALUES (?, 1, '최초 속성', 'REMOVE', 'NONE')
			""", entity);
		int genre = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000);
		for (int id = genre; id <= genre + 2; id++) {
			jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (?, '테스트 장르')", id);
		}
		jdbc.update("INSERT INTO patch_plan_genre (patch_plan_id, genre_id) VALUES (?, ?), (?, ?)",
			planId, genre + 1, planId, genre);
		jdbc.update("INSERT INTO patch_plan_genre (patch_plan_id, genre_id) VALUES (?, ?)", otherPlan, genre + 2);
		jdbc.update("INSERT INTO game_tag (appid, tag_id, weight) VALUES (?, ?, 1)", gameId, genre + 2);
		jdbc.update("UPDATE game SET name = '현재 게임 이름' WHERE appid = ?", gameId);
		jdbc.update("INSERT INTO my_game (member_id, appid, created_at) VALUES (?, ?, now())", memberId, gameId);
		jdbc.update("DELETE FROM my_game WHERE member_id = ? AND appid = ?", memberId, gameId);

		var data = data(planId);
		assertThat(data.size()).isEqualTo(8);
		assertThat(data.path("planId").asLong()).isEqualTo(planId);
		assertThat(data.path("gameId").asLong()).isEqualTo(gameId);
		assertThat(data.path("gameTitle").asText()).isEqualTo("현재 게임 이름");
		assertThat(data.path("rawText").asText()).isEqualTo(raw);
		assertThat(data.path("restatement").size()).isEqualTo(1);
		assertThat(data.path("restatement").path("text").asText()).isEqualTo(restatement);
		assertThat(data.path("genreIds")).isEqualTo(mapper.readTree("[" + genre + "," + (genre + 1) + "]"));
		assertThat(data.path("createdAt").asText()).isEqualTo("2026-09-21T14:32:01.123456+09:00");
		assertThat(data.path("confirmedSlots")).isEqualTo(mapper.readTree("""
			[{"target":{"name":"Axebot","role":"ENEMY"},"attribute":"체력","changeType":"MODIFY",
			"direction":"INCREASE","magnitude":"20%","scope":"고통 4 이상"},
			{"target":{"name":"","role":"SYSTEM"},"attribute":"","changeType":"ADD",
			"direction":"NOT_APPLICABLE","magnitude":"","scope":""},
			{"target":{"name":"Wraith","role":"UNKNOWN"},"attribute":"등장 빈도","changeType":"MODIFY",
			"direction":"UNKNOWN","magnitude":null,"scope":null}]
			"""));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void missingOrEmptyRestatementReturnsEmptyTextAndNoGenreMeansAllGenres(boolean hasRestatement) throws Exception {
		long planId = plan(memberId, "원문");
		if (hasRestatement) restatement(planId, "");
		var data = data(planId);
		assertThat(data.path("restatement")).isEqualTo(mapper.readTree("{\"text\":\"\"}"));
		assertThat(data.path("genreIds").isArray()).isTrue();
		assertThat(data.path("genreIds")).isEmpty();
		assertThat(data.path("confirmedSlots").isArray()).isTrue();
		assertThat(data.path("confirmedSlots")).isEmpty();
	}

	@Test
	void missingAndOtherMembersPlansReturnSameNotFoundResponse() throws Exception {
		long otherPlan = plan(otherMemberId, "비공개 원문");
		restatement(otherPlan, "비공개 해석");
		for (long planId : new long[] {Long.MAX_VALUE, otherPlan}) {
			var result = mvc.perform(request(planId).queryParam("memberId", Long.toString(otherMemberId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PATCH_PLAN_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("기획안 내역을 찾을 수 없습니다.")).andReturn();
			var body = mapper.readTree(result.getResponse().getContentAsByteArray());
			assertThat(body.size()).isEqualTo(3);
			assertThat(body.has("responsedAt")).isTrue();
			assertThat(body.toString()).doesNotContain("비공개", "data", "success", "errors");
		}
	}

	private MockHttpServletRequestBuilder request(long planId) {
		return get("/members/me/patch-plans/{planId}", planId)
			.header("Authorization", "Bearer " + tokens.issueAccessToken(memberId));
	}

	private JsonNode data(long planId) throws Exception {
		return mapper.readTree(mvc.perform(request(planId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray()).path("data");
	}

	private long member() {
		return jdbc.queryForObject("""
			INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id
			""", Long.class);
	}

	private long plan(long member, String raw) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, created_at) VALUES (?, ?, ?, ?) RETURNING patch_plan_id
			""", Long.class, member, gameId, raw, CREATED_AT);
	}

	private void restatement(long planId, String text) {
		jdbc.update("INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, ?, ?)",
			planId, text, CREATED_AT);
	}

	private void confirmed(long plan, int order, String name, String role, String attribute,
		String changeType, String direction, String magnitude, String scope) {
		jdbc.update("""
			INSERT INTO patch_plan_confirmed_slot (patch_plan_id, slot_order, target_name, target_role,
				attribute, change_type, direction, magnitude, scope, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", plan, order, name, role, attribute, changeType, direction, magnitude, scope, CREATED_AT);
	}
}
