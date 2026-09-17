package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MyGameListIntegrationTest {

	private static final String PATH = "/members/me/games";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties jwtProperties;
	@Autowired private GameListService gameLists;

	private long memberId;
	private long baseId;
	private int tagId;
	private int nextGame;

	@BeforeEach
	void prepareIsolatedFixtures() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = newMember("ACTIVE");
		do {
			baseId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid between ? and ?", Integer.class,
			baseId, baseId + 100) != 0);
		do {
			tagId = ThreadLocalRandom.current().nextInt(1_000_000_000, 1_900_000_000);
		} while (jdbc.queryForObject("select count(*) from tag where tag_id between ? and ?", Integer.class,
			tagId, tagId + 3) != 0);
	}

	@Test
	void noRegistrationsReturnsEmptyIndependentPageWithDefaultLimit() throws Exception {
		long other = newMember("ACTIVE");
		register(other, game("다른 회원 게임", 10, 100));
		game("미등록 게임", 20, 200);
		var body = response(request(memberId));
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("code").asText()).isEqualTo("200");
		assertThat(body.path("message").asText()).isEqualTo("성공했습니다.");
		assertThat(body.path("success").asBoolean()).isTrue();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.path("data")).isEqualTo(mapper.readTree("""
			{"items":[],"page":{"limit":10,"nextCursor":null,"hasNext":false,"totalCount":0}}
			"""));
	}

	@Test
	void usesAuthenticatedMemberAndOmitsIsMineEverywhere() throws Exception {
		long mine = game("내 게임", 30, 100);
		long theirs = game("다른 게임", 10, 900);
		long other = newMember("ACTIVE");
		register(memberId, mine);
		register(other, theirs);
		var data = data(request(memberId).queryParam("memberId", Long.toString(other)));
		assertThat(ids(data)).containsExactly(mine);
		assertThat(data.path("page").path("totalCount").asLong()).isEqualTo(1);
		assertThat(data.toString()).doesNotContain("isMine");
		assertThat(ids(data(request(other)))).containsExactly(theirs);
	}

	@Test
	void mapsNullableSummaryAndPreservesZeroStatistics() throws Exception {
		long id = game("빈 정보", null, null);
		register(memberId, id);
		var item = data(request(memberId)).path("items").get(0);
		assertThat(item.size()).isEqualTo(6);
		assertNull(item, "capsuleImageUrl", "positiveRate");
		assertThat(item.path("tags").isArray()).isTrue();
		assertThat(item.path("tags")).isEmpty();
		var summary = item.path("gameSummary");
		assertThat(summary.size()).isEqualTo(10);
		assertThat(summary.path("id").asLong()).isEqualTo(id);
		assertThat(summary.path("title").asText()).isEqualTo("빈 정보");
		assertNull(summary, "headerImageUrl", "releasedOn", "developer", "description", "reviewCount", "latestPatch");
		assertThat(summary.path("playModes")).isEmpty();
		assertThat(summary.path("userTags")).isEmpty();
		jdbc.update("update game set store_review_count = 0, store_positive_pct = 0 where appid = ?", id);
		var zero = data(request(memberId)).path("items").get(0);
		assertThat(zero.path("positiveRate").asInt(-1)).isZero();
		assertThat(zero.path("gameSummary").path("reviewCount").asInt(-1)).isZero();
	}

	@Test
	void mapsCapsuleKoreanReleaseDateTagsModesAndLatestPatch() throws Exception {
		long id = game("상세 정보", 70, 1200);
		register(memberId, id);
		jdbc.update("""
			update game set capsule_path = 'hash/capsule_616x353.jpg', developer = '개발사',
			short_description = '설명', release_ts = ? where appid = ?
			""", OffsetDateTime.parse("2026-09-08T15:30:00Z"), id);
		for (int n = 0; n < 3; n++) {
			jdbc.update("insert into tag (tag_id, name_ko) values (?, ?)", tagId + n, "태그" + n);
			jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, ?)", id, tagId + n,
				n == 0 ? 1 : 10);
		}
		jdbc.update("insert into game_play_mode (appid, play_mode_id) values (?, 2), (?, 1)", id, id);
		String older = patch(id, "이전 패치", "2026-08-01T00:00:00Z", true);
		String latest = patch(id, "최신 패치", "2026-09-01T00:00:00Z", true);
		patch(id, "일반 공지", "2026-09-02T00:00:00Z", false);
		var item = data(request(memberId)).path("items").get(0);
		String url = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + id + "/hash/capsule_616x353.jpg";
		assertThat(item.path("capsuleImageUrl").asText()).isEqualTo(url);
		assertThat(item.path("tags").get(0).path("id").asInt()).isEqualTo(tagId + 1);
		assertThat(item.path("tags").get(1).path("id").asInt()).isEqualTo(tagId + 2);
		assertThat(item.path("tags").get(2).path("id").asInt()).isEqualTo(tagId);
		var summary = item.path("gameSummary");
		assertThat(summary.path("headerImageUrl").asText()).isEqualTo(url);
		assertThat(summary.path("releasedOn").asText()).isEqualTo("2026-09-09");
		assertThat(summary.path("developer").asText()).isEqualTo("개발사");
		assertThat(summary.path("description").asText()).isEqualTo("설명");
		assertThat(summary.path("reviewCount").asInt()).isEqualTo(1200);
		assertThat(summary.path("latestPatch").asText()).isEqualTo("최신 패치");
		assertThat(summary.path("playModes")).isEqualTo(mapper.readTree("[\"멀티플레이어\",\"싱글 플레이어\"]"));
		assertThat(summary.path("userTags")).isEqualTo(mapper.readTree("[\"태그1\",\"태그2\",\"태그0\"]"));
		assertThat(jdbc.queryForObject("select count(*) from news where gid in (?, ?)", Integer.class, older, latest)).isEqualTo(2);
	}

	@Test
	void trimsSearchIgnoresCaseAndTreatsSqlWildcardsLiterally() throws Exception {
		long exact = game("Slay %_\\ Hero", 50, 100);
		long wildcard = game("Slay ABC Hero", 50, 100);
		register(memberId, exact);
		register(memberId, wildcard);
		assertThat(ids(data(request(memberId).queryParam("search", " slAY %_\\ ")))).containsExactly(exact);
		assertThat(ids(data(request(memberId).queryParam("search", "   ")))).containsExactly(exact, wildcard);
		assertThat(ids(data(request(memberId).queryParam("search", "' OR 1=1 --")))).isEmpty();
	}

	@Test
	void genresUseOrDeduplicateAndCountOnlyFilteredRegistrations() throws Exception {
		long first = game("필터 게임", 10, 100);
		long second = game("필터 게임", 20, 100);
		long noTag = game("필터 게임", 30, 100);
		long unregistered = game("필터 게임", 0, 100);
		for (long id : List.of(first, second, noTag)) register(memberId, id);
		for (int n = 0; n < 3; n++) jdbc.update("insert into tag (tag_id, name_ko) values (?, ?)", tagId + n, "장르" + n);
		jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, 1), (?, ?, 1), (?, ?, 1), (?, ?, 1)",
			first, tagId, first, tagId + 1, second, tagId + 1, unregistered, tagId);
		var filtered = data(request(memberId).queryParam("genreIds", tagId + "," + (tagId + 1) + "," + tagId)
			.queryParam("limit", "1").queryParam("search", "필터"));
		assertThat(ids(filtered)).containsExactly(first);
		assertThat(filtered.path("page").path("totalCount").asLong()).isEqualTo(2);
		assertThat(filtered.path("page").path("hasNext").asBoolean()).isTrue();
		assertThat(ids(data(request(memberId).queryParam("genreIds", Integer.toString(tagId + 2))))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"POSITIVE_RATE_ASC", "REVIEW_COUNT_DESC", "RELEASE_DATE_DESC", "REACTION_CHANGE_DESC"})
	void traversesEverySortWithTiesNullsAndNoDuplicateRows(String sort) throws Exception {
		long first = game("첫 게임", 10, 100);
		long second = game("동률 게임", 10, 100);
		long third = game("다음 게임", 80, 10);
		long missing = game("값 없음", null, null);
		long alsoMissing = game("또 값 없음", null, null);
		for (long id : List.of(first, second, third, missing, alsoMissing)) register(memberId, id);
		for (long id : List.of(first, second, third)) {
			jdbc.update("update game set release_ts = ? where appid = ?",
				OffsetDateTime.parse(id == third ? "2025-01-01T00:00:00Z"
					: id == first ? "2025-12-31T16:00:00Z" : "2026-01-01T02:00:00Z"), id);
			String gid = patch(id, "패치", "2026-08-01T00:00:00Z", true);
			stat(gid, id, id == first ? -50 : id == second ? 50 : 10);
		}
		List<Long> seen = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 5; page++) {
			var request = request(memberId).queryParam("sort", sort).queryParam("limit", "1");
			if (cursor != null) request.queryParam("cursor", cursor);
			var data = data(request);
			seen.addAll(ids(data));
			var metadata = data.path("page");
			assertThat(metadata.path("totalCount").asLong()).isEqualTo(5);
			assertThat(metadata.path("hasNext").asBoolean()).isEqualTo(page < 4);
			cursor = metadata.path("nextCursor").isNull() ? null : metadata.path("nextCursor").asText();
			if (page < 4) assertThat(cursor).isNotBlank(); else assertThat(cursor).isNull();
		}
		assertThat(seen).containsExactly(first, second, third, missing, alsoMissing);
	}

	@Test
	void reactionSortUsesOnlyLatestPatchNotAnOlderAvailableStatistic() throws Exception {
		long missingLatest = game("최신 통계 없음", 10, 100);
		long available = game("최신 통계 있음", 10, 100);
		register(memberId, missingLatest);
		register(memberId, available);
		stat(patch(missingLatest, "이전", "2026-01-01T00:00:00Z", true), missingLatest, 99);
		patch(missingLatest, "새 패치", "2026-02-01T00:00:00Z", true);
		stat(patch(available, "현재", "2026-02-01T00:00:00Z", true), available, -2);
		assertThat(ids(data(request(memberId).queryParam("sort", "REACTION_CHANGE_DESC")))).containsExactly(available, missingLatest);
	}

	@Test
	void reflectsRegistrationAndUnregistrationIncludingRemovedCursorAnchor() throws Exception {
		long first = game("첫 게임", 10, 100);
		long second = game("다음 게임", 20, 100);
		long added = game("새 게임", 30, 100);
		register(memberId, first);
		register(memberId, second);
		String cursor = data(request(memberId).queryParam("limit", "1")).path("page").path("nextCursor").asText();
		mvc.perform(delete("/games/{gameId}/my-game", first).header(HttpHeaders.AUTHORIZATION, auth(memberId)))
			.andExpect(status().isOk());
		mvc.perform(post("/games/{gameId}/my-game", added).header(HttpHeaders.AUTHORIZATION, auth(memberId)))
			.andExpect(status().isOk());
		var next = data(request(memberId).queryParam("limit", "100").queryParam("cursor", cursor));
		assertThat(ids(next)).containsExactly(second, added);
		assertThat(next.path("page").path("totalCount").asLong()).isEqualTo(2);
		for (long id : List.of(second, added)) {
			mvc.perform(delete("/games/{gameId}/my-game", id).header(HttpHeaders.AUTHORIZATION, auth(memberId)))
				.andExpect(status().isOk());
		}
		var empty = data(request(memberId).queryParam("cursor", cursor));
		assertThat(ids(empty)).isEmpty();
		assertThat(empty.path("page").path("totalCount").asLong()).isZero();
		assertThat(empty.path("page").path("nextCursor").isNull()).isTrue();
		assertThat(empty.path("page").path("hasNext").asBoolean()).isFalse();
	}

	@Test
	void rejectsChangedFiltersSortAndOtherMembersCursor() throws Exception {
		register(memberId, game("게임1", 10, 100));
		register(memberId, game("게임2", 20, 100));
		String cursor = data(request(memberId).queryParam("limit", "1")).path("page").path("nextCursor").asText();
		assertError(request(memberId).queryParam("cursor", cursor).queryParam("search", "게임"), 400);
		assertError(request(memberId).queryParam("cursor", cursor).queryParam("sort", "REVIEW_COUNT_DESC"), 400);
		assertError(request(memberId).queryParam("cursor", cursor).queryParam("genreIds", "1"), 400);
		assertError(request(newMember("ACTIVE")).queryParam("cursor", cursor), 400);
	}

	@Test
	void wholeListAndMyListCursorsCannotBeExchanged() throws Exception {
		String title = "scope-" + UUID.randomUUID();
		register(memberId, game(title, 10, 100));
		register(memberId, game(title, 20, 100));
		var query = GameListQuery.of(title, GameListSort.POSITIVE_RATE_ASC, 1, null);
		String allCursor = gameLists.getGames(memberId, query, null, GameListScope.ALL).data().page().nextCursor();
		assertThat(allCursor).isNotBlank();
		assertError(request(memberId).queryParam("search", title).queryParam("cursor", allCursor), 400);
		String myCursor = data(request(memberId).queryParam("search", title).queryParam("limit", "1"))
			.path("page").path("nextCursor").asText();
		assertThatThrownBy(() -> gameLists.getGames(memberId, query, myCursor, GameListScope.ALL))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@CsvSource({"limit,0", "limit,101", "limit,abc", "sort,UNKNOWN", "genreIds,0", "genreIds,-1", "genreIds,abc", "genreIds,2147483648", "cursor,not-a-cursor"})
	void invalidQueryReturnsSafe400(String key, String value) throws Exception {
		assertError(request(memberId).queryParam(key, value), 400);
	}

	@Test
	void rejectsOverlongSearchAndMalformedCursor() throws Exception {
		assertError(request(memberId).queryParam("search", "가".repeat(101)), 400);
		assertError(request(memberId).queryParam("cursor", Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), 400);
		assertError(request(memberId).queryParam("genreIds", "1,,2"), 400);
	}

	@Test
	void rejectsMissingInvalidExpiredRefreshAndInactiveMemberCredentials() throws Exception {
		assertError(get(PATH), 401);
		var pastTokens = new JwtTokenProvider(jwtProperties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
		for (String token : List.of("private-invalid-token", pastTokens.issueAccessToken(memberId), tokens.issueRefreshToken(memberId))) {
			assertError(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 401);
		}
		assertError(request(newMember("WITHDRAWN")), 401);
	}

	private long newMember(String state) {
		return members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
			.email(UUID.randomUUID() + "@example.com").status(state).createdAt(Instant.now()).build()).getMemberId();
	}

	private long game(String title, Integer positive, Integer count) {
		long id = baseId + nextGame++;
		jdbc.update("insert into game (appid, name, store_positive_pct, store_review_count, collected_at) values (?, ?, ?, ?, now())",
			id, title, positive, count);
		return id;
	}

	private void register(long member, long game) {
		jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", member, game);
	}

	private String patch(long game, String title, String published, boolean isPatch) {
		String gid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		jdbc.update("insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at) values (?, ?, ?, '', ?, ?, now())",
			gid, game, title, OffsetDateTime.parse(published), isPatch);
		return gid;
	}

	private void stat(String gid, long game, int delta) {
		jdbc.update("insert into patch_stat (gid, appid, patched_at, before_review_count, after_review_count, delta_pct) values (?, ?, now(), 10, 10, ?)",
			gid, game, delta);
	}

	private String auth(long member) { return "Bearer " + tokens.issueAccessToken(member); }

	private MockHttpServletRequestBuilder request(long member) {
		return get(PATH).header(HttpHeaders.AUTHORIZATION, auth(member));
	}

	private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
		return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
	}

	private JsonNode data(MockHttpServletRequestBuilder request) throws Exception { return response(request).path("data"); }

	private List<Long> ids(JsonNode data) {
		List<Long> ids = new ArrayList<>();
		data.path("items").forEach(item -> ids.add(item.path("id").asLong()));
		return ids;
	}

	private void assertNull(JsonNode node, String... fields) {
		for (String field : fields) {
			assertThat(node.has(field)).as(field + " present").isTrue();
			assertThat(node.path(field).isNull()).as(field + " null").isTrue();
		}
	}

	private void assertError(MockHttpServletRequestBuilder request, int statusCode) throws Exception {
		var response = mvc.perform(request).andExpect(status().is(statusCode)).andReturn().getResponse();
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.path("code").isTextual()).isTrue();
		assertThat(body.path("message").isTextual()).isTrue();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		assertThat(body.toString()).doesNotContain("private-", "Exception", "rejectedValue", "java.lang");
		if (statusCode == 401) {
			assertThat(body.path("code").asText()).isEqualTo("UNAUTHORIZED");
			assertThat(body.size()).isEqualTo(3);
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
	}
}
