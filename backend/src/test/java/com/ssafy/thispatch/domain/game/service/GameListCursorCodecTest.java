package com.ssafy.thispatch.domain.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListFilters;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec.Boundary;
import com.ssafy.thispatch.global.exception.BusinessException;

class GameListCursorCodecTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final GameListCursorCodec codec = new GameListCursorCodec(mapper);
	// Codec에는 repository가 정규화한 검색어가 전달된다. 정규화 동등성은 API 통합 테스트에서 검증한다.
	private final GameListQuery query = GameListQuery.of("slay", GameListSort.POSITIVE_RATE_ASC, 10, "2,1,2");

	@ParameterizedTest
	@EnumSource(GameListSort.class)
	void preservesLongIdsTypedValuesAndNullBoundaries(GameListSort sort) {
		var request = GameListQuery.of(null, sort, 1, null);
		String value = switch (sort) {
			case POSITIVE_RATE_ASC -> "70";
			case REVIEW_COUNT_DESC -> "2147483647";
			case REACTION_CHANGE_DESC -> "30.25";
			case RELEASE_DATE_DESC -> "2026-09-09";
		};
		for (Boundary boundary : List.of(new Boundary(Long.MAX_VALUE, value), new Boundary(4_000_000_000L, null))) {
			assertThat(codec.decode(codec.encode(request, GameListScope.ALL, 1, boundary), request,
				GameListScope.ALL, 2)).isEqualTo(boundary);
		}
	}

	@Test
	void canonicalFiltersAndLimitChangesKeepCursorValid() {
		var boundary = new Boundary(4_000_000_000L, "70");
		var cursor = codec.encode(query, GameListScope.ALL, 1, boundary);
		var equivalent = GameListQuery.of("slay", GameListSort.POSITIVE_RATE_ASC, 100, "1,2");
		assertThat(codec.decode(cursor, equivalent, GameListScope.ALL, 1)).isEqualTo(boundary);
	}

	@Test
	void rejectsDifferentSearchSortGenresScopeAndMyGameOwner() {
		String cursor = codec.encode(query, GameListScope.ALL, 1, new Boundary(4, "70"));
		for (var changed : List.of(
			GameListQuery.of("other", query.sort(), 10, "1,2"),
			GameListQuery.of("slay", GameListSort.REVIEW_COUNT_DESC, 10, "1,2"),
			GameListQuery.of("slay", query.sort(), 10, "1"))) {
			invalid(cursor, changed, GameListScope.ALL, 1);
		}
		invalid(cursor, query, GameListScope.MY, 1);
		String mine = codec.encode(query, GameListScope.MY, 1, new Boundary(4, "70"));
		invalid(mine, query, GameListScope.ALL, 1);
		invalid(mine, query, GameListScope.MY, 2);
	}

	@Test
	void rejectsMalformedIncompleteCoercedOverflowDuplicateAndTrailingJson() throws Exception {
		for (String raw : List.of("", "***", "e30", encoded("null"), encoded("[]"))) {
			invalid(raw, query, GameListScope.ALL, 1);
		}
		String cursor = codec.encode(query, GameListScope.ALL, 1, new Boundary(4, "70"));
		String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		for (String bad : List.of(json + " {}", json.replace("\"version\":2", "\"version\":1"),
			json.replace("\"id\":4", "\"id\":4.5"), json.replace("\"id\":4", "\"id\":9223372036854775808"),
			json.replace("\"id\":4", "\"id\":4,\"id\":5"), json.replace("\"value\":\"70\"", "\"value\":{}"),
			json.replace("\"value\":\"70\"", "\"value\":\"private-invalid-value\""))) {
			invalid(encoded(bad), query, GameListScope.ALL, 1);
		}
		var missing = mapper.readTree(json).deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("value");
		invalid(encoded(missing.toString()), query, GameListScope.ALL, 1);
	}

	@Test
	void rejectsInvalidDateAndNumericValues() {
		for (var sort : GameListSort.values()) {
			var request = GameListQuery.of(null, sort, 1, null);
			String cursor = codec.encode(request, GameListScope.ALL, 1, new Boundary(1, "not-a-sort-value"));
			invalid(cursor, request, GameListScope.ALL, 1);
		}
	}

	private void invalid(String cursor, GameListQuery request, GameListScope scope, long memberId) {
		assertThatThrownBy(() -> codec.decode(cursor, request, scope, memberId))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@EnumSource(GameListScope.class)
	void includesEveryNewFilterInCursorAndNormalizesDeveloper(GameListScope scope) throws Exception {
		var filters = GameListFilters.of("2020", "2026", "100", "1000", "70", "90", "  VaLvE ");
		var request = GameListQuery.of(null, query.sort(), 1, null, filters);
		var boundary = new Boundary(4, "70");
		String cursor = codec.encode(request, scope, 1, boundary);
		var equivalent = GameListQuery.of(null, query.sort(), 100, null,
			GameListFilters.of("2020", "2026", "100", "1000", "70", "90", "valve"));
		assertThat(codec.decode(cursor, equivalent, scope, 1)).isEqualTo(boundary);
		for (var changed : List.of(
			new GameListFilters(2021, 2026, 100, 1000, 70, 90, "valve"),
			new GameListFilters(2020, 2025, 100, 1000, 70, 90, "valve"),
			new GameListFilters(2020, 2026, 101, 1000, 70, 90, "valve"),
			new GameListFilters(2020, 2026, 100, 999, 70, 90, "valve"),
			new GameListFilters(2020, 2026, 100, 1000, 71, 90, "valve"),
			new GameListFilters(2020, 2026, 100, 1000, 70, 89, "valve"),
			new GameListFilters(2020, 2026, 100, 1000, 70, 90, "other"),
			GameListFilters.NONE)) {
			invalid(cursor, GameListQuery.of(null, query.sort(), 1, null, changed), scope, 1);
		}
		var unfiltered = GameListQuery.of(null, query.sort(), 1, null);
		invalid(codec.encode(unfiltered, scope, 1, boundary), request, scope, 1);
	}

	private String encoded(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}
}
