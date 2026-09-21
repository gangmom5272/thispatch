package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec.Boundary;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

class PatchPlanListCursorCodecTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final PatchPlanListCursorCodec codec = new PatchPlanListCursorCodec(mapper);
	private final Boundary boundary = new Boundary(4_000_000_000L,
		OffsetDateTime.parse("2026-09-21T14:32:01.123456+09:00"));

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = {3_000_000_000L})
	void roundTripPreservesLongIdsFilterAndMicroseconds(Long gameId) {
		var decoded = codec.decode(codec.encode(5_000_000_000L, gameId, boundary), 5_000_000_000L, gameId);
		assertThat(decoded.planId()).isEqualTo(boundary.planId());
		assertThat(decoded.createdAt().toInstant()).isEqualTo(boundary.createdAt().toInstant());
		assertThat(codec.decode(null, 1, gameId)).isNull();
	}

	@Test
	void rejectsAnotherMemberAndEveryFilterMismatch() {
		String all = codec.encode(1, null, boundary);
		String filtered = codec.encode(1, 730L, boundary);
		invalid(all, 2, null);
		invalid(filtered, 2, 730L);
		invalid(all, 1, 730L);
		invalid(filtered, 1, null);
		invalid(filtered, 1, 570L);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "%%%", "not-a-cursor"})
	void rejectsMalformedBase64(String cursor) {
		invalid(cursor, 1, null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "[]", "{}", "{", "{} {}",
		"{\"version\":1,\"version\":1,\"memberId\":1,\"gameId\":null,\"planId\":1,\"createdAt\":\"2026-09-21T00:00:00Z\"}"})
	void rejectsInvalidJsonAndDuplicateFields(String json) {
		invalid(encoded(json), 1, null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"version=2", "version=1.0", "version=4294967297", "memberId=1.0",
		"memberId=9223372036854775808", "gameId=0", "gameId=\"null\"", "planId=0", "planId=-1",
		"planId=9223372036854775808", "planId=\"1\"", "createdAt=null", "createdAt=\"invalid\"",
		"createdAt=\"2026-02-30T00:00:00Z\"", "createdAt=\"2026-09-21T00:00:00\"", "extra=true"})
	void rejectsInvalidFieldTypesValuesAndExtraFields(String replacement) throws Exception {
		var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
			Base64.getUrlDecoder().decode(codec.encode(1, null, boundary)));
		String[] parts = replacement.split("=", 2);
		node.set(parts[0], mapper.readTree(parts[1]));
		invalid(encoded(node.toString()), 1, null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"version", "memberId", "gameId", "planId", "createdAt"})
	void rejectsMissingFields(String field) throws Exception {
		var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
			Base64.getUrlDecoder().decode(codec.encode(1, null, boundary)));
		node.remove(field);
		invalid(encoded(node.toString()), 1, null);
	}

	private String encoded(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private void invalid(String cursor, long memberId, Long gameId) {
		assertThatThrownBy(() -> codec.decode(cursor, memberId, gameId)).isInstanceOfSatisfying(
			BusinessException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));
	}
}
