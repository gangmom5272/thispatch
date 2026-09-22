package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.EmbeddingRequest;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.Target;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

/** 실제 AI 호출과 테스트 DB를 연결한다. 데이터는 테스트 트랜잭션 종료 시 롤백한다. */
@SpringBootTest(properties = "app.ai.base-url=${AI_TEST_BASE_URL:}")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "AI_INTEGRATION_TEST", matches = "true")
class PatchLiveAiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiPatchClient ai;
    @Autowired private JwtTokenProvider tokens;

    @Test
    void structuresPlanSearchesAllOutcomeGroupsAndReadsOriginalThroughRealAi() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
        assertThat(ai.health().ready()).as("AI_TEST_BASE_URL의 AI 서버가 준비되어 있어야 한다").isTrue();
        long gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
        int genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
        jdbc.update("insert into game (appid, name, collected_at) values (?, 'AI integration game', now())", gameId);
        jdbc.update("insert into tag (tag_id, name_ko) values (?, 'AI integration genre')", genreId);
        jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, 1)", gameId, genreId);
        long memberId = jdbc.queryForObject("""
            insert into member (login_type, status, created_at)
            values ('LOCAL', 'ACTIVE', now()) returning member_id
            """, Long.class);
        String authorization = "Bearer " + tokens.issueAccessToken(memberId);
        String planText = "적 액스봇의 체력을 20% 증가시킵니다.";

        var structureResult = mvc.perform(post("/games/{gameId}/plan-structures", gameId)
                .header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("text", planText))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.genreIds[0]").value(genreId))
            .andReturn();
        var structure = mapper.readValue(structureResult.getResponse().getContentAsByteArray(), PlanStructureResponse.class);
        assertThat(structure.data().slots()).as("실제 모델이 기획안의 변경점을 추출해야 한다").isNotEmpty();
        var slot = structure.data().slots().get(0);
        assertThat(slot.targetName()).isNotBlank();
        assertThat(slot.attribute()).isNotBlank();
        var confirmed = new ConfirmedSlot(new Target(slot.targetName(), slot.targetRole()), slot.attribute(),
            slot.changeType(), slot.direction(), slot.magnitude(), slot.scope());
        var sentence = CaseSearchService.toPlanChange(confirmed).sourceSentence();
        var embedding = ai.embed(new EmbeddingRequest("", List.of(sentence)));

        // 저장 벡터도 실제 AI로 생성한다. 검색 품질 평가가 아닌 HTTP·SQL·응답 매핑 통합 검증이다.
        String vector = embedding.embeddings().get(0).stream().map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
        String firstId = "live-" + gameId + "a";
        String middleId = "live-" + gameId + "b";
        String lastId = "live-" + gameId + "c";
        insertPatch(gameId, firstId, "2026-01-01T00:00:00Z", 67, confirmed, vector, embedding.model());
        insertPatch(gameId, middleId, "2026-01-11T00:00:00Z", 71, confirmed, vector, embedding.model());
        insertPatch(gameId, lastId, "2026-01-25T00:00:00Z", 73, confirmed, vector, embedding.model());

        var request = new CaseSearchRequest(structure.data().planId(), List.of(confirmed), List.of(genreId), null);
        var searchResult = mvc.perform(post("/games/{gameId}/case-searches", gameId)
                .header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.totalCount").value(3))
            .andExpect(jsonPath("$.data.groups[0].outcome").value("NEGATIVE_SHIFT"))
            .andExpect(jsonPath("$.data.groups[1].outcome").value("NO_CHANGE"))
            .andExpect(jsonPath("$.data.groups[2].outcome").value("POSITIVE_SHIFT"))
            .andExpect(jsonPath("$.data.groups[0].cases[0].deltaPp").value(-3))
            .andExpect(jsonPath("$.data.groups[1].cases[0].avgPatchIntervalDays").value(10))
            .andExpect(jsonPath("$.data.groups[1].cases[0].nextPatchIntervalDays").value(14))
            .andExpect(jsonPath("$.data.groups[1].cases[0].followUpSpeedRatio").value(1.4))
            .andExpect(jsonPath("$.data.groups[2].cases[0].deltaPp").value(3))
            .andReturn();
        var groups = mapper.readTree(searchResult.getResponse().getContentAsByteArray()).path("data").path("groups");
        for (var group : groups) {
            assertThat(group.path("caseCount").asInt()).isEqualTo(1);
            var resultCase = group.path("cases").get(0);
            assertThat(resultCase.path("commonalitySummary").asText()).isNotBlank();
            assertThat(resultCase.path("comparison").path("commonalities").isArray()).isTrue();
        }

        mvc.perform(get("/games/{gameId}/patches/{patchId}", gameId, middleId)
                .header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.patchId").value(middleId))
            .andExpect(jsonPath("$.data.patchedOn").value("2026-01-11"))
            .andExpect(jsonPath("$.data.bodyFormat").value("PLAIN_TEXT"))
            .andExpect(jsonPath("$.data.body").value("Enemy health increased by 20%."));
    }

    private void insertPatch(long gameId, String gid, String date, int afterRate,
        ConfirmedSlot slot, String vector, String model) {
        var publishedAt = OffsetDateTime.parse(date);
        jdbc.update("""
            insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
            values (?, ?, 'Enemy health update', '[b]Enemy health increased by 20%.[/b]', ?, true, now())
            """, gid, gameId, publishedAt);
        jdbc.update("""
            insert into patch_stat (gid, appid, patched_at, before_review_count, before_positive_pct,
                after_review_count, after_positive_pct, delta_pct)
            values (?, ?, ?, 20, 70, 30, ?, ?)
            """, gid, gameId, publishedAt, afterRate, afterRate - 70);
        long chunkId = jdbc.queryForObject("""
            insert into patch_chunk (gid, seq, text, extraction_status, embedding_status, embedding, embedding_model)
            values (?, 1, 'Enemy health increased by 20%.', 'succeeded', 'succeeded', cast(? as vector), ?)
            returning chunk_id
            """, Long.class, gid, vector, model);
        int inserted = jdbc.update("""
            insert into patch_change (chunk_id, change_type_id, direction_id, target_type_id, evidence_quote, validation_status)
            select ?, ct.change_type_id, d.direction_id, t.target_type_id, 'Enemy health increased by 20%.', 'valid'
            from patch_change_type ct cross join patch_change_direction d cross join patch_change_target_type t
            where ct.code = ? and d.code = ? and t.code = ?
            """, chunkId, slot.changeType().name().toLowerCase(java.util.Locale.ROOT),
            slot.direction().name().toLowerCase(java.util.Locale.ROOT), slot.target().role().name().toLowerCase(java.util.Locale.ROOT));
        assertThat(inserted).isEqualTo(1);
    }
}
