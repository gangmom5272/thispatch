package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.global.exception.BusinessException;

class AiPatchValidationTest {

	private MockRestServiceServer server;
	private AiPatchClient client;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder().baseUrl("http://ai.test:8100");
		server = MockRestServiceServer.bindTo(builder).build();
		var rest = builder.build();
		client = new AiPatchClient(rest, rest, true);
		server.expect(requestTo("http://ai.test:8100/health"))
			.andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "{}", "{\"change_type\":\"invented\",\"direction\":\"increase\",\"target_type\":\"enemy\",\"conditions\":[],\"restatement\":\"text\",\"source_sentence\":\"text\"}"})
	void invalidPlanChangeBecomesAiUnavailable(String change) {
		respond("/plan/structure", "{\"changes\":[" + change + "]}");
		assertUnavailable(() -> client.structure(new PlanRequest("", "기획안 내용입니다")));
	}

	@Test
	void missingEmbeddingModelDoesNotReturnEmptySearchResults() throws Exception {
		respond("/embed/query", new ObjectMapper().writeValueAsString(Map.of(
			"dim", 512, "embeddings", List.of(Collections.nCopies(512, 0.125)))));
		assertUnavailable(() -> client.embed(new EmbeddingRequest("", List.of("문장"))));
	}

	@Test
	void zeroVectorCannotBeUsedForCosineSearch() throws Exception {
		respond("/embed/query", new ObjectMapper().writeValueAsString(Map.of(
			"dim", 512, "model", "gemma", "embeddings", List.of(Collections.nCopies(512, 0.0)))));
		assertUnavailable(() -> client.embed(new EmbeddingRequest("", List.of("문장"))));
	}

	@Test
	void nullComparisonPointBecomesAiUnavailable() {
		respond("/cases/compare", "{\"gid\":\"10\",\"common\":[null],\"differences\":[]}");
		assertUnavailable(() -> client.compare(new CompareRequest(new PlanSlots(List.of(), List.of()),
			new CaseInput("10", "game", "", List.of(), List.of(), null, null), false)));
	}

	@Test
	void missingCardTextBecomesAiUnavailable() {
		respond("/cases/cards", "{\"cards\":[{\"gid\":\"10\",\"common\":\"same\"}],\"patterns\":[]}");
		assertUnavailable(() -> client.cards(new CardsRequest(new PlanSlots(List.of(), List.of()),
			List.of(new CaseInput("10", "game", "", List.of(), List.of(), null, null)), 20)));
	}

	private void respond(String path, String body) {
		server.expect(requestTo("http://ai.test:8100" + path)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void assertUnavailable(Runnable operation) {
		assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
		server.verify();
	}
}
