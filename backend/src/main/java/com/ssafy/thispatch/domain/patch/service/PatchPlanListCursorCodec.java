package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.global.exception.CommonErrorCode.INVALID_REQUEST;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PatchPlanListCursorCodec {

	private final ObjectMapper mapper;

	public String encode(long memberId, Long gameId, Boundary boundary) {
		var node = mapper.createObjectNode();
		node.put("version", 1);
		node.put("memberId", memberId);
		node.put("gameId", gameId);
		node.put("planId", boundary.planId());
		node.put("createdAt", boundary.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString());
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(node));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize patch plan list cursor", exception);
		}
	}

	public Boundary decode(String cursor, long memberId, Long gameId) {
		if (cursor == null) {
			return null;
		}
		try {
			JsonNode node = mapper.readerFor(JsonNode.class)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
				.readTree(Base64.getUrlDecoder().decode(cursor));
			if (node == null || !node.isObject() || node.size() != 5
				|| !node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt()
				|| node.path("version").intValue() != 1
				|| !positiveLong(node.path("memberId")) || node.path("memberId").longValue() != memberId
				|| !positiveLong(node.path("planId")) || !node.path("createdAt").isTextual()
				|| (gameId == null ? !node.path("gameId").isNull()
					: !positiveLong(node.path("gameId")) || node.path("gameId").longValue() != gameId)) {
				throw new BusinessException(INVALID_REQUEST);
			}
			return new Boundary(node.get("planId").longValue(), OffsetDateTime.parse(node.get("createdAt").textValue()));
		} catch (IOException | IllegalArgumentException | DateTimeException exception) {
			throw new BusinessException(INVALID_REQUEST);
		}
	}

	private boolean positiveLong(JsonNode node) {
		return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0;
	}

	public record Boundary(long planId, OffsetDateTime createdAt) {
	}
}
