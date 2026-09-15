package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GameDetailIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;

	private long memberId;
	private long gameId;

	@BeforeEach
	void prepareFixturesInTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = newMember("ACTIVE");
		do {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid in (?, ?)", Integer.class,
			gameId, gameId + 1) != 0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, ?, ?)",
			gameId, "상세 조회 게임", OffsetDateTime.parse("2026-09-09T08:00:00Z"));
	}

	@Test
	void mapsStoredFieldsAndAllTagsInWeightThenIdOrderWithoutChangingRows() throws Exception {
		jdbc.update("""
			update game set capsule_path = ?, short_description = ?, release_ts = ?,
			store_review_count = ?, store_positive_pct = ? where appid = ?
			""", "hash/capsule_616x353.jpg", "게임 설명", OffsetDateTime.parse("2026-09-08T15:30:00Z"),
			1234, 70, gameId);
		int first;
		do {
			first = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		} while (jdbc.queryForObject("select count(*) from tag where tag_id between ? and ?", Integer.class,
			first, first + 3) != 0);
		for (int offset : List.of(2, 0, 1, 3)) {
			jdbc.update("insert into tag (tag_id, name_ko) values (?, ?)", first + offset, "태그-" + offset);
		}
		// ID 순서와 가중치 순서가 다르고, 높은 가중치 두 태그의 값이 같다.
		for (int offset : List.of(2, 0, 1)) {
			jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, ?)",
				gameId, first + offset, offset == 0 ? 10 : 50);
		}
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, gameId);
		var gameBefore = jdbc.queryForMap("select * from game where appid = ?", gameId);
		var registrationBefore = jdbc.queryForMap("select * from my_game where member_id = ? and appid = ?", memberId, gameId);

		JsonNode data = data(request(memberId, gameId));
		assertThat(data.size()).isEqualTo(10);
		assertThat(data.get("id").longValue()).isEqualTo(gameId);
		assertThat(data.get("title").asText()).isEqualTo("상세 조회 게임");
		assertThat(data.get("description").asText()).isEqualTo("게임 설명");
		assertThat(data.get("capsuleImageUrl").asText()).isEqualTo(
			"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + gameId + "/hash/capsule_616x353.jpg");
		assertThat(data.get("releasedOn").asText()).isEqualTo("2026-09-09");
		assertThat(data.get("reviewCount").intValue()).isEqualTo(1234);
		assertThat(data.get("positiveRate").intValue()).isEqualTo(70);
		assertThat(data.get("lastCollectedAt").asText()).isEqualTo("2026-09-09T08:00:00Z");
		assertThat(data.get("isMine").asBoolean()).isTrue();
		assertThat(data.get("tags").size()).isEqualTo(3);
		for (int index = 0; index < 3; index++) {
			int offset = List.of(1, 2, 0).get(index);
			var tag = data.get("tags").get(index);
			assertThat(tag.size()).isEqualTo(2);
			assertThat(tag.get("id").intValue()).isEqualTo(first + offset);
			assertThat(tag.get("name").asText()).isEqualTo("태그-" + offset);
		}
		assertThat(jdbc.queryForMap("select * from game where appid = ?", gameId)).isEqualTo(gameBefore);
		assertThat(jdbc.queryForMap("select * from my_game where member_id = ? and appid = ?", memberId, gameId))
			.isEqualTo(registrationBefore);
	}

	@Test
	void gameWithNoTagsOrOptionalValuesStillReturns200AndExplicitNulls() throws Exception {
		var data = data(request(memberId, gameId));
		for (String field : List.of("capsuleImageUrl", "description", "releasedOn", "reviewCount", "positiveRate")) {
			assertThat(data.has(field)).as(field).isTrue();
			assertThat(data.get(field).isNull()).as(field).isTrue();
		}
		assertThat(data.get("tags").isArray()).isTrue();
		assertThat(data.get("tags").size()).isZero();
		assertThat(data.get("isMine").asBoolean()).isFalse();
		assertThat(data.get("lastCollectedAt").asText()).isEqualTo("2026-09-09T08:00:00Z");
	}

	@Test
	void preservesZeroStatisticsAndOneHundredPercent() throws Exception {
		jdbc.update("update game set store_review_count = 0, store_positive_pct = 0 where appid = ?", gameId);
		request(memberId, gameId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reviewCount").value(0)).andExpect(jsonPath("$.data.positiveRate").value(0));
		jdbc.update("update game set store_review_count = 1, store_positive_pct = 100 where appid = ?", gameId);
		request(memberId, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.positiveRate").value(100));
	}

	@Test
	void isMineTracksOnlyCurrentMembersTargetRegistrationAndReflectsUnregister() throws Exception {
		long otherMember = newMember("ACTIVE");
		jdbc.update("insert into game (appid, name, collected_at) values (?, '다른 게임', now())", gameId + 1);
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, gameId + 1);
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", otherMember, gameId);
		request(memberId, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.isMine").value(false));
		request(otherMember, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.isMine").value(true));
		mvc.perform(post("/games/{gameId}/my-game", gameId).header(HttpHeaders.AUTHORIZATION, authorization(memberId)))
			.andExpect(status().isOk());
		request(memberId, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.isMine").value(true));
		mvc.perform(delete("/games/{gameId}/my-game", gameId).header(HttpHeaders.AUTHORIZATION, authorization(memberId)))
			.andExpect(status().isOk());
		request(memberId, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.isMine").value(false));
		request(otherMember, gameId).andExpect(status().isOk()).andExpect(jsonPath("$.data.isMine").value(true));
	}

	@Test
	void unknownGameReturnsGameNotFound() throws Exception {
		request(memberId, gameId + 1).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
	}

	@Test
	void missingAndWithdrawnMembersCannotReadGameDetails() throws Exception {
		long withdrawn = newMember("WITHDRAWN");
		request(withdrawn, gameId).andExpect(status().isUnauthorized());
		long removed = newMember("ACTIVE");
		jdbc.update("delete from member where member_id = ?", removed);
		request(removed, gameId).andExpect(status().isUnauthorized());
	}

	private long newMember(String state) {
		return members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.email(UUID.randomUUID() + "@example.com").nickname(null).status(state)
			.createdAt(Instant.now()).build()).getMemberId();
	}

	private String authorization(long id) {
		return "Bearer " + tokens.issueAccessToken(id);
	}

	private ResultActions request(long currentMember, long id) throws Exception {
		return mvc.perform(get("/games/{gameId}", id).header(HttpHeaders.AUTHORIZATION, authorization(currentMember)));
	}

	private JsonNode data(ResultActions result) throws Exception {
		return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
			.get("data");
	}
}
