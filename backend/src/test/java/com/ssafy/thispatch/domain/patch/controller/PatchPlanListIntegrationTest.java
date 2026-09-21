package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec.Boundary;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PatchPlanListIntegrationTest {

	private static final String PATH = "/members/me/patch-plans";
	private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-21T14:32:01.123456+09:00");

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private PatchPlanListCursorCodec cursors;
	@MockitoBean private AiPatchClient ai;
	@MockitoBean private PatchSearchRepository searchRepository;
	private long memberId;
	private long otherMemberId;
	private long gameId;
	private long otherGameId;

	@BeforeEach
	void prepareTestData() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = member();
		otherMemberId = member();
		gameId = game();
		otherGameId = game();
	}

	@AfterEach
	void listDoesNotStructureOrSearch() {
		verifyNoInteractions(ai, searchRepository);
	}

	@Test
	void pagesByTimestampThenIdAndCountsAllMatchingHistory() throws Exception {
		long older = plan(memberId, gameId, "같은 원문", CREATED_AT.minusSeconds(1));
		long tiedFirst = plan(memberId, gameId, "같은 원문", CREATED_AT);
		long tiedSecond = plan(memberId, otherGameId, "같은 원문", CREATED_AT);
		long newer = plan(memberId, gameId, "같은 원문", CREATED_AT.plusNanos(1000));
		plan(otherMemberId, gameId, "다른 회원", CREATED_AT.plusDays(1));
		List<Long> seen = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 4; page++) {
			var request = request(memberId).queryParam("limit", "1");
			if (cursor != null) request.queryParam("cursor", cursor);
			var data = data(request);
			assertThat(data.path("page").path("totalCount").asLong()).isEqualTo(4);
			assertThat(data.path("items").size()).isEqualTo(1);
			seen.add(data.path("items").get(0).path("planId").asLong());
			assertThat(data.path("page").path("hasNext").asBoolean()).isEqualTo(page < 3);
			cursor = data.path("page").path("nextCursor").isNull() ? null : data.path("page").path("nextCursor").asText();
		}
		assertThat(cursor).isNull();
		assertThat(seen).containsExactly(newer, tiedSecond, tiedFirst, older);
		var filtered = data(request(memberId).queryParam("gameId", Long.toString(gameId)));
		assertThat(ids(filtered)).containsExactly(newer, tiedFirst, older);
		assertThat(filtered.path("page").path("totalCount").asLong()).isEqualTo(3);
		assertThat(data(request(otherMemberId)).path("page").path("totalCount").asLong()).isEqualTo(1);
	}

	@Test
	void returnsCurrentGameNameAfterUnregisterAndUsesOnlyConfirmedSlots() throws Exception {
		long planId = plan(memberId, gameId, "원문", CREATED_AT);
		long entity = jdbc.queryForObject("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role, warning_code, warning_message)
			VALUES (?, 1, '최초 미확인 대상', 'UNKNOWN', 'UNKNOWN_ENTITY', '당시 경고') RETURNING patch_plan_entity_id
			""", Long.class, planId);
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction)
			VALUES (?, 1, '체력', 'MODIFY', 'INCREASE')
			""", entity);
		confirmed(planId, 1, "Wraith", "UNKNOWN");
		confirmed(planId, 2, "Wraith", "UNKNOWN");
		confirmed(planId, 3, "Wraith", "ENEMY");
		confirmed(planId, 4, "wraith", "UNKNOWN");
		confirmed(planId, 5, "", "UNKNOWN");
		confirmed(planId, 6, "", "UNKNOWN");
		confirmed(planId, 7, "확정 대상", "ENEMY");
		jdbc.update("INSERT INTO my_game (member_id, appid, created_at) VALUES (?, ?, now())", memberId, gameId);
		jdbc.update("DELETE FROM my_game WHERE member_id = ? AND appid = ?", memberId, gameId);
		jdbc.update("UPDATE game SET name = '수정된 현재 이름' WHERE appid = ?", gameId);
		var item = data(request(memberId)).path("items").get(0);
		assertThat(item.path("gameTitle").asText()).isEqualTo("수정된 현재 이름");
		assertThat(item.path("slotCount").asInt()).isEqualTo(7);
		assertThat(item.path("unknownEntityCount").asInt()).isEqualTo(3);
		assertThat(item.path("createdAt").asText()).isEqualTo("2026-09-21T14:32:01.123456+09:00");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 199, 200, 201})
	void previewPreservesOriginalCharactersAndTruncatesAt200(int length) throws Exception {
		String raw = "가".repeat(length);
		plan(memberId, gameId, raw, CREATED_AT);
		var item = data(request(memberId)).path("items").get(0);
		assertThat(item.path("rawTextPreview").asText()).isEqualTo("가".repeat(Math.min(length, 200)));
		assertThat(item.path("slotCount").asInt()).isZero();
		assertThat(item.path("unknownEntityCount").asInt()).isZero();
	}

	@Test
	void previewDoesNotSplitEmojiOrTrimWhitespace() throws Exception {
		String prefix = " \n" + "가".repeat(197) + "😀";
		plan(memberId, gameId, prefix + "이후 생략", CREATED_AT);
		var preview = data(request(memberId)).path("items").get(0).path("rawTextPreview").asText();
		assertThat(preview).isEqualTo(prefix);
		assertThat(preview.codePointCount(0, preview.length())).isEqualTo(200);
	}

	@Test
	void emptyResultsAndEmptyPageKeepCorrectTotals() throws Exception {
		assertEmpty(data(request(memberId)), 0);
		long planId = plan(memberId, gameId, "원문", CREATED_AT);
		assertEmpty(data(request(memberId).queryParam("gameId", Long.toString(otherGameId))), 0);
		assertEmpty(data(request(memberId).queryParam("gameId", Long.toString(Long.MAX_VALUE))), 0);
		String cursor = cursors.encode(memberId, null, new Boundary(planId, CREATED_AT));
		assertEmpty(data(request(memberId).queryParam("cursor", cursor)), 1);
	}

	@Test
	void filteredCursorPagesAndCannotBeReusedByOtherMemberOrFilter() throws Exception {
		long older = plan(memberId, gameId, "원문", CREATED_AT);
		long newer = plan(memberId, gameId, "원문", CREATED_AT);
		plan(memberId, otherGameId, "다른 게임", CREATED_AT.plusSeconds(1));
		var first = data(request(memberId).queryParam("gameId", Long.toString(gameId)).queryParam("limit", "1"));
		assertThat(ids(first)).containsExactly(newer);
		String cursor = first.path("page").path("nextCursor").asText();
		var second = data(request(memberId).queryParam("gameId", Long.toString(gameId)).queryParam("cursor", cursor));
		assertThat(ids(second)).containsExactly(older);
		assertThat(second.path("page").path("totalCount").asLong()).isEqualTo(2);
		for (var request : List.of(request(otherMemberId).queryParam("gameId", Long.toString(gameId)),
			request(memberId), request(memberId).queryParam("gameId", Long.toString(otherGameId)))) {
			mvc.perform(request.queryParam("cursor", cursor)).andExpect(status().isBadRequest());
		}
	}

	private MockHttpServletRequestBuilder request(long member) {
		return get(PATH).header("Authorization", "Bearer " + tokens.issueAccessToken(member));
	}

	private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
		return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray()).path("data");
	}

	private List<Long> ids(JsonNode data) {
		List<Long> ids = new ArrayList<>();
		data.path("items").forEach(item -> ids.add(item.path("planId").asLong()));
		return ids;
	}

	private void assertEmpty(JsonNode data, long total) {
		assertThat(data.path("items")).isEmpty();
		assertThat(data.path("page").path("hasNext").asBoolean()).isFalse();
		assertThat(data.path("page").path("nextCursor").isNull()).isTrue();
		assertThat(data.path("page").path("totalCount").asLong()).isEqualTo(total);
	}

	private long member() {
		return jdbc.queryForObject("""
			INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now()) RETURNING member_id
			""", Long.class);
	}

	private long game() {
		long id = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '기획안 목록 테스트 게임', now())", id);
		return id;
	}

	private long plan(long member, long game, String raw, OffsetDateTime time) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, created_at) VALUES (?, ?, ?, ?) RETURNING patch_plan_id
			""", Long.class, member, game, raw, time);
	}

	private void confirmed(long plan, int order, String name, String role) {
		jdbc.update("""
			INSERT INTO patch_plan_confirmed_slot
			(patch_plan_id, slot_order, target_name, target_role, attribute, change_type, direction, created_at)
			VALUES (?, ?, ?, ?, '체력', 'MODIFY', 'INCREASE', ?)
			""", plan, order, name, role, CREATED_AT);
	}
}
