package com.ssafy.thispatch.domain.game.service;

import static com.ssafy.thispatch.global.exception.CommonErrorCode.INVALID_REQUEST;

import java.io.IOException;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListFilters;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GameListCursorCodec {

	private final ObjectMapper mapper;

	public String encode(GameListQuery query, GameListScope scope, long memberId, Boundary boundary) {
		var node = mapper.createObjectNode();
		node.put("version", 2);
		node.put("scope", scope.name());
		if (scope == GameListScope.MY) {
			node.put("memberId", memberId);
		} else {
			node.putNull("memberId");
		}
		node.put("search", query.search());
		node.put("sort", query.sort().name());
		node.set("genreIds", mapper.valueToTree(query.genreIds()));
		if (!query.filters().equals(GameListFilters.NONE)) {
			node.set("filters", mapper.valueToTree(query.filters()));
		}
		node.put("id", boundary.id());
		node.put("value", boundary.value());
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(node));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize game list cursor", exception);
		}
	}

	public Boundary decode(String cursor, GameListQuery query, GameListScope scope, long memberId) {
		if (cursor == null) {
			return null;
		}
		try {
			JsonNode node = mapper.readerFor(JsonNode.class)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
				.readTree(Base64.getUrlDecoder().decode(cursor));
			if (node == null || !node.isObject() || node.size() != (node.has("filters") ? 9 : 8)
				|| !node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt()
				|| node.path("version").intValue() != 2
				|| !node.path("scope").isTextual() || !scope.name().equals(node.path("scope").textValue())
				|| !node.path("search").isTextual() || !query.search().equals(node.path("search").textValue())
				|| !node.path("sort").isTextual() || !query.sort().name().equals(node.path("sort").textValue())
				|| !mapper.valueToTree(query.genreIds()).equals(node.path("genreIds"))
				|| !matchesFilters(node, query.filters())
				|| !node.path("id").isIntegralNumber() || !node.path("id").canConvertToLong()
				|| !(node.path("value").isNull() || node.path("value").isTextual())) {
				throw new BusinessException(INVALID_REQUEST);
			}
			if (scope == GameListScope.MY
				? !node.path("memberId").isIntegralNumber() || !node.path("memberId").canConvertToLong()
					|| node.path("memberId").longValue() != memberId
				: !node.path("memberId").isNull()) {
				throw new BusinessException(INVALID_REQUEST);
			}
			String value = node.get("value").isNull() ? null : node.get("value").textValue();
			query.sort().cursorValue(value);
			return new Boundary(node.get("id").longValue(), value);
		} catch (IOException | IllegalArgumentException | java.time.DateTimeException exception) {
			throw new BusinessException(INVALID_REQUEST);
		}
	}

	public record Boundary(long id, String value) {
	}

	private boolean matchesFilters(JsonNode node, GameListFilters filters) {
		// 신규 조건이 없는 기존 커서는 유지하되, 필터를 추가한 요청에는 재사용하지 못하게 한다.
		if (!node.has("filters")) {
			return filters.equals(GameListFilters.NONE);
		}
		return mapper.valueToTree(filters).equals(node.get("filters"));
	}
}
