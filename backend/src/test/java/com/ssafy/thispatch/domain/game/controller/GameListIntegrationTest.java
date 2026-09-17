package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GameListIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private GameListService service;

	private long memberId;
	private long otherMemberId;
	private long firstId;
	private String prefix;
	private int firstTag;

	@BeforeEach
	void prepareIsolatedFixturesInTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = member("ACTIVE");
		otherMemberId = member("ACTIVE");
		prefix = "list-" + UUID.randomUUID();
		do {
			firstId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid between ? and ?", Integer.class,
			firstId, firstId + 5) != 0);
		Integer[] rates = {70, 0, 70, 100, null, null};
		Integer[] counts = {100, 50, 100, 0, null, null};
		String[] releases = {"2026-01-01T15:00:00Z", "2026-01-02T14:00:00Z",
			"2026-01-02T15:00:00Z", "2026-01-01T00:00:00Z", null, null};
		for (int i = 0; i < 6; i++) {
			jdbc.update("""
				insert into game (appid, name, store_positive_pct, store_review_count, release_ts, collected_at)
				values (?, ?, ?, ?, ?, now())
				""", firstId + i, prefix + "-" + i, rates[i], counts[i],
				releases[i] == null ? null : OffsetDateTime.parse(releases[i]));
		}
		register(memberId, 0);
		register(otherMemberId, 1);
		do {
			firstTag = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		} while (jdbc.queryForObject("select count(*) from tag where tag_id in (?, ?)", Integer.class, firstTag, firstTag + 1) != 0);
		jdbc.update("insert into tag (tag_id, name_ko) values (?, '액션'), (?, 'RPG')", firstTag, firstTag + 1);
		jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, 10), (?, ?, 50), (?, ?, 10), (?, ?, 10)",
			firstId, firstTag, firstId, firstTag + 1, firstId + 1, firstTag, firstId + 2, firstTag + 1);
	}

	@ParameterizedTest
	@EnumSource(GameListSort.class)
	void pagesEverySortAcrossTiesAndNullsWithoutDuplicatesAndWithFullTotalCount(GameListSort sort) throws Exception {
		preparePatches();
		List<Integer> offsets = switch (sort) {
			case POSITIVE_RATE_ASC -> List.of(1, 0, 2, 3, 4, 5);
			case REVIEW_COUNT_DESC -> List.of(0, 2, 1, 3, 4, 5);
			case REACTION_CHANGE_DESC -> List.of(1, 2, 0, 3, 4, 5);
			case RELEASE_DATE_DESC -> List.of(2, 0, 1, 3, 4, 5);
		};
		var expected = offsets.stream().map(i -> firstId + i).toList();
		for (int limit : List.of(1, 2, 100)) {
			String cursor = null;
			List<Long> actual = new ArrayList<>();
			for (int page = 0; page < 7; page++) {
				var data = request(memberId, sort, limit, cursor, prefix, null);
				assertThat(data.at("/page/limit").intValue()).isEqualTo(limit);
				assertThat(data.at("/page/totalCount").longValue()).isEqualTo(6);
				data.get("items").forEach(item -> actual.add(item.get("id").longValue()));
				if (!data.at("/page/hasNext").asBoolean()) {
					assertThat(data.at("/page/nextCursor").isNull()).isTrue();
					break;
				}
				cursor = data.at("/page/nextCursor").asText();
				assertThat(cursor).isNotBlank();
			}
			assertThat(actual).containsExactlyElementsOf(expected);
		}
	}

	@Test
	void returnsAllGamesWithCurrentMembersRegistrationAndCompleteSummary() throws Exception {
		jdbc.update("update game set capsule_path = ?, developer = ?, short_description = ? where appid = ?",
			"hash/capsule.jpg", "개발사", "설명", firstId);
		jdbc.update("insert into game_play_mode (appid, play_mode_id) values (?, 2), (?, 1)", firstId, firstId);
		preparePatches();
		var before = jdbc.queryForMap("select * from game where appid = ?", firstId);
		var data = request(memberId, GameListSort.REVIEW_COUNT_DESC, 10, null, prefix, null);
		assertThat(data.get("items").size()).isEqualTo(6);
		var item = data.get("items").get(0);
		assertThat(item.size()).isEqualTo(7);
		assertThat(item.get("id").longValue()).isEqualTo(firstId);
		assertThat(item.get("isMine").asBoolean()).isTrue();
		assertThat(item.get("tags").size()).isEqualTo(2);
		assertThat(item.get("tags").get(0).get("id").intValue()).isEqualTo(firstTag + 1);
		assertThat(item.get("tags").get(1).get("id").intValue()).isEqualTo(firstTag);
		var summary = item.get("gameSummary");
		assertThat(summary.size()).isEqualTo(10);
		assertThat(summary.get("headerImageUrl")).isEqualTo(item.get("capsuleImageUrl"));
		assertThat(summary.get("headerImageUrl").asText()).isEqualTo(
			"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + firstId + "/hash/capsule.jpg");
		assertThat(summary.get("releasedOn").asText()).isEqualTo("2026-01-02");
		assertThat(summary.get("developer").asText()).isEqualTo("개발사");
		assertThat(summary.get("description").asText()).isEqualTo("설명");
		assertThat(summary.get("userTags")).isEqualTo(mapper.readTree("[\"RPG\",\"액션\"]"));
		assertThat(summary.get("playModes")).isEqualTo(mapper.readTree("[\"멀티플레이어\",\"싱글 플레이어\"]"));
		assertThat(summary.get("latestPatch").asText()).isEqualTo("최신 패치-0");
		assertThat(summary.get("reviewCount").intValue()).isEqualTo(100);
		for (var game : data.get("items")) {
			assertThat(game.get("isMine").asBoolean()).isEqualTo(game.get("id").longValue() == firstId);
		}
		var other = request(otherMemberId, GameListSort.REVIEW_COUNT_DESC, 10, null, prefix, null);
		for (var game : other.get("items")) {
			assertThat(game.get("isMine").asBoolean()).isEqualTo(game.get("id").longValue() == firstId + 1);
		}
		assertThat(jdbc.queryForMap("select * from game where appid = ?", firstId)).isEqualTo(before);
	}

	@Test
	void nullableFieldsAndZeroValuesArePreserved() throws Exception {
		var data = request(memberId, GameListSort.POSITIVE_RATE_ASC, 10, null, prefix, null);
		assertThat(data.get("items").get(0).get("positiveRate").intValue()).isZero();
		var noData = data.get("items").get(4);
		assertThat(noData.get("positiveRate").isNull()).isTrue();
		assertThat(noData.get("capsuleImageUrl").isNull()).isTrue();
		assertThat(noData.get("tags").isEmpty()).isTrue();
		var summary = noData.get("gameSummary");
		for (String field : List.of("headerImageUrl", "releasedOn", "developer", "description", "reviewCount", "latestPatch")) {
			assertThat(summary.get(field).isNull()).as(field).isTrue();
		}
		assertThat(summary.get("playModes").isEmpty()).isTrue();
		assertThat(summary.get("userTags").isEmpty()).isTrue();
	}

	@Test
	void combinesCaseInsensitiveLiteralSearchWithOrGenresAndDeduplicatesMatches() throws Exception {
		var data = request(memberId, GameListSort.POSITIVE_RATE_ASC, 10, null, " " + prefix.toUpperCase() + " ",
			firstTag + "," + (firstTag + 1) + "," + firstTag);
		assertThat(ids(data)).containsExactly(firstId + 1, firstId, firstId + 2);
		assertThat(data.at("/page/totalCount").longValue()).isEqualTo(3);
		jdbc.update("update game set name = ? where appid = ?", prefix + " 100%_!\\' SlAy", firstId);
		jdbc.update("update game set name = ? where appid = ?", prefix + " 100xx!\\' SlAy", firstId + 1);
		var literal = request(memberId, GameListSort.POSITIVE_RATE_ASC, 5, null,
			"  " + prefix.toUpperCase() + " 100%_!\\' slay ", Integer.toString(firstTag));
		assertThat(ids(literal)).containsExactly(firstId);
	}

	@Test
	void emptyResultsUnknownGenresAndPageAfterFinalBoundaryHaveStablePageShape() throws Exception {
		var empty = request(memberId, GameListSort.POSITIVE_RATE_ASC, 5, null, prefix + "-missing", null);
		assertEmpty(empty, 0);
		var unknown = request(memberId, GameListSort.POSITIVE_RATE_ASC, 5, null, prefix, "2147483647");
		assertEmpty(unknown, 0);
		var first = request(memberId, GameListSort.REVIEW_COUNT_DESC, 5, null, prefix, null);
		var cursor = first.at("/page/nextCursor").asText();
		jdbc.update("delete from game where appid = ?", firstId + 5);
		assertEmpty(request(memberId, GameListSort.REVIEW_COUNT_DESC, 5, cursor, prefix, null), 5);
	}

	@Test
	void deletingCursorGameDoesNotInvalidateItsBoundaryAndChangedFilterIsRejected() throws Exception {
		var first = request(memberId, GameListSort.POSITIVE_RATE_ASC, 1, null, prefix, null);
		String cursor = first.at("/page/nextCursor").asText();
		long removed = firstId + 1;
		jdbc.update("delete from my_game where appid = ?", removed);
		jdbc.update("delete from game_tag where appid = ?", removed);
		jdbc.update("delete from game where appid = ?", removed);
		var next = request(memberId, GameListSort.POSITIVE_RATE_ASC, 100, cursor, prefix, null);
		assertThat(ids(next)).containsExactly(firstId, firstId + 2, firstId + 3, firstId + 4, firstId + 5);
		assertThat(next.at("/page/totalCount").longValue()).isEqualTo(5);
		mvc.perform(get("/games").param("search", prefix + "-changed").param("cursor", cursor)
			.header(HttpHeaders.AUTHORIZATION, auth(memberId))).andExpect(status().isBadRequest());
	}

	@Test
	void commonMyScopeRestrictsRowsAndRejectsMyCursorAtAllEndpoint() throws Exception {
		register(memberId, 2);
		var query = GameListQuery.of(prefix, GameListSort.REVIEW_COUNT_DESC, 1, null);
		var mine = service.getGames(memberId, query, null, GameListScope.MY).data();
		assertThat(mine.page().totalCount()).isEqualTo(2);
		assertThat(mine.items()).extracting(item -> item.id()).containsExactly(firstId);
		mvc.perform(get("/games").param("search", prefix).param("sort", "REVIEW_COUNT_DESC")
			.param("cursor", mine.page().nextCursor()).header(HttpHeaders.AUTHORIZATION, auth(memberId)))
			.andExpect(status().isBadRequest());
		var other = service.getGames(otherMemberId, query, null, GameListScope.MY).data();
		assertThat(other.page().totalCount()).isEqualTo(1);
		assertThat(other.items()).extracting(item -> item.id()).containsExactly(firstId + 1);
		jdbc.update("delete from my_game where member_id = ? and appid = ?", memberId, firstId);
		assertThat(service.getGames(memberId, query, null, GameListScope.MY).data().page().totalCount()).isEqualTo(1);
	}

	@Test
	void missingAndInactiveMembersCannotReadList() throws Exception {
		long inactive = member("WITHDRAWN");
		long removed = member("ACTIVE");
		jdbc.update("delete from member where member_id = ?", removed);
		for (long id : List.of(inactive, removed)) {
			mvc.perform(get("/games").header(HttpHeaders.AUTHORIZATION, auth(id)))
				.andExpect(status().isUnauthorized());
		}
	}

	private void preparePatches() {
		String[] deltas = {"10.00", "-30.25", "30.25", "0.00", null, null};
		for (int i = 0; i < 6; i++) {
			// 문자열 gid 동률 기준도 검증: 같은 게시 시각의 최신 패치가 더 큰 gid다.
			String old = Long.toString(firstId * 10 + i) + "a";
			String latest = Long.toString(firstId * 10 + i) + "b";
			for (String gid : List.of(old, latest)) {
				jdbc.update("""
					insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
					values (?, ?, ?, '', ?, true, now())
					""", gid, firstId + i, gid.equals(latest) ? "최신 패치-" + i : "이전 패치",
					OffsetDateTime.parse("2026-01-01T00:00:00Z"));
			}
			jdbc.update("""
				insert into patch_stat (gid, appid, patched_at, before_review_count, after_review_count, delta_pct)
				values (?, ?, now(), 10, 10, 99)
				""", old, firstId + i);
			if (i != 4) {
				jdbc.update("""
					insert into patch_stat (gid, appid, patched_at, before_review_count, after_review_count, delta_pct)
					values (?, ?, now(), 10, 10, ?)
					""", latest, firstId + i, deltas[i] == null ? null : new java.math.BigDecimal(deltas[i]));
			}
			jdbc.update("""
				insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
				values (?, ?, '새 일반 공지', '', ?, false, now())
				""", Long.toString(firstId * 10 + i) + "c", firstId + i, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
		}
	}

	private JsonNode request(long member, GameListSort sort, int limit, String cursor, String search, String genres) throws Exception {
		var request = get("/games").param("sort", sort.name()).param("limit", Integer.toString(limit))
			.param("search", search).header(HttpHeaders.AUTHORIZATION, auth(member));
		if (cursor != null) {
			request.param("cursor", cursor);
		}
		if (genres != null) {
			request.param("genreIds", genres);
		}
		return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsByteArray()).get("data");
	}

	private List<Long> ids(JsonNode data) {
		List<Long> ids = new ArrayList<>();
		data.get("items").forEach(item -> ids.add(item.get("id").longValue()));
		return ids;
	}

	private void assertEmpty(JsonNode data, long totalCount) {
		assertThat(data.get("items").isEmpty()).isTrue();
		assertThat(data.at("/page/nextCursor").isNull()).isTrue();
		assertThat(data.at("/page/hasNext").asBoolean()).isFalse();
		assertThat(data.at("/page/totalCount").longValue()).isEqualTo(totalCount);
	}

	private long member(String state) {
		return members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.email(UUID.randomUUID() + "@example.com").nickname(null).status(state).createdAt(Instant.now()).build())
			.getMemberId();
	}

	private void register(long member, int offset) {
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", member, firstId + offset);
	}

	private String auth(long member) {
		return "Bearer " + tokens.issueAccessToken(member);
	}
}