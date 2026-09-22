package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.global.exception.BusinessException;

class AiPatchClientTest {

	private MockRestServiceServer server;
	private AiPatchClient client;
	private final AtomicLong clock = new AtomicLong();
	private static final String GID = "18446744073709551615";

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("http://ai.test:8100");
		server = MockRestServiceServer.bindTo(builder).build();
		var rest = builder.build();
		client = new AiPatchClient(rest, rest, true, clock::get);
	}

	@Test
	void structureDecodesSlotsWithoutChangingAiCodeValues() {
		ready();
		server.expect(requestTo("http://ai.test:8100/plan/structure")).andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.text").value("적 체력을 올린다"))
			.andRespond(withSuccess("""
				{"changes":[{"change_seq":1,"change_type":"modify","direction":"increase",
				"target_type":"enemy","target":"Axebot","attribute":"health","values":"20%",
				"conditions":["hard"],"restatement":"체력 증가","source_sentence":"적 체력을 올린다"}],
				"model":"qwen","prompt_version":"v1","elapsed_ms":30}
				""", MediaType.APPLICATION_JSON));
		var response = client.structure(new PlanRequest("게임", "적 체력을 올린다"));
		assertThat(response.changes().get(0).targetType()).isEqualTo("enemy");
		assertThat(response.changes().get(0).changeSeq()).isEqualTo(1);
		server.verify();
	}

	@Test
	void restateUsesSnakeCaseSlots() {
		ready();
		server.expect(requestTo("http://ai.test:8100/plan/restate"))
			.andExpect(jsonPath("$.changes[0].change_type").value("modify"))
			.andExpect(jsonPath("$.changes[0].target_type").value("enemy"))
			.andRespond(withSuccess("{\"restatements\":[\"체력 증가\"],\"summary\":\"변경 요약\"}", MediaType.APPLICATION_JSON));
		assertThat(client.restate(new RestateRequest(plan().changes())).summary()).isEqualTo("변경 요약");
		server.verify();
	}

	@Test
	void embeddingPreserves512Dimensions() throws Exception {
		ready();
		String body = new ObjectMapper().writeValueAsString(new EmbeddingResponse(
			List.of(Collections.nCopies(512, 0.125)), 512, "embeddinggemma-300m-bf16-512"));
		server.expect(requestTo("http://ai.test:8100/embed/query"))
			.andExpect(jsonPath("$.sentences[0]").value("체력 증가"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertThat(client.embed(new EmbeddingRequest("게임", List.of("체력 증가"))).embeddings().get(0)).hasSize(512);
		server.verify();
	}

	@Test
	void invalidVectorDoesNotReachSearch() {
		ready();
		server.expect(requestTo("http://ai.test:8100/embed/query"))
			.andRespond(withSuccess("{\"embeddings\":[[1.0]],\"dim\":512,\"model\":\"gemma\"}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.embed(new EmbeddingRequest("게임", List.of("체력 증가"))))
			.isInstanceOf(BusinessException.class);
		server.verify();
	}

	@Test
	void cardsSendCandidateDataAndKeepUnsignedIdAsString() {
		ready();
		server.expect(requestTo("http://ai.test:8100/cases/cards"))
			.andExpect(jsonPath("$.min_group_n").value(20)).andExpect(jsonPath("$.cases[0].gid").value(GID))
			.andExpect(jsonPath("$.cases[0].changes[0].evidence_quote").value("증가"))
			.andRespond(withSuccess("{\"cards\":[{\"gid\":\"" + GID + "\",\"common\":\"공통\",\"difference\":\"차이\"}],\"patterns\":[],\"note\":null}", MediaType.APPLICATION_JSON));
		assertThat(client.cards(new CardsRequest(plan(), List.of(candidate()), 20)).cards().get(0).gid()).isEqualTo(GID);
		server.verify();
	}

	@Test
	void comparisonUsesCaseFieldAndSupportsTemplateFallback() {
		ready();
		server.expect(requestTo("http://ai.test:8100/cases/compare"))
			.andExpect(jsonPath("$.case.gid").value(GID)).andExpect(jsonPath("$.use_llm").value(true))
			.andExpect(jsonPath("$.case_input").doesNotExist())
			.andRespond(withSuccess("{\"gid\":\"" + GID + "\",\"common\":[],\"differences\":[],\"llm_used\":false,\"elapsed_ms\":1}", MediaType.APPLICATION_JSON));
		assertThat(client.compare(new CompareRequest(plan(), candidate(), true)).llmUsed()).isFalse();
		server.verify();
	}

	@Test
	void unavailableModelsPreventCalls() {
		server.expect(requestTo("http://ai.test:8100/health"))
			.andRespond(withSuccess("{\"ready\":false}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.structure(new PlanRequest("", "적 체력을 올린다")))
			.isInstanceOf(BusinessException.class);
		server.verify();
	}

	@Test
	void upstreamErrorIsNotExposedOrRetried() {
		ready();
		server.expect(requestTo("http://ai.test:8100/plan/structure"))
			.andRespond(withServerError().body("private server details"));
		assertThatThrownBy(() -> client.structure(new PlanRequest("", "적 체력을 올린다")))
			.isInstanceOf(BusinessException.class).hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage());
		server.verify();
	}

	@Test
	void concurrentCallsShareOneSuccessfulHealthCheck() throws Exception {
		ready();
		server.expect(ExpectedCount.times(8), requestTo("http://ai.test:8100/plan/restate"))
			.andRespond(withSuccess("{\"restatements\":[],\"summary\":\"요약\"}", MediaType.APPLICATION_JSON));
		var callers = Executors.newFixedThreadPool(8);
		var started = new CountDownLatch(8);
		var release = new CountDownLatch(1);
		List<Future<RestateResponse>> results = new ArrayList<>();
		try {
			for (int index = 0; index < 8; index++) {
				results.add(callers.submit(() -> {
					started.countDown();
					assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
					return client.restate(new RestateRequest(plan().changes()));
				}));
			}
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			for (var response : results) assertThat(response.get(5, TimeUnit.SECONDS).summary()).isEqualTo("요약");
			server.verify();
		} finally {
			release.countDown();
			callers.shutdownNow();
		}
	}

	@Test
	void successfulHealthIsReusedForFiveSeconds() {
		ready();
		expectRestate();
		expectRestate();
		ready();
		expectRestate();
		client.restate(new RestateRequest(plan().changes()));
		clock.set(Duration.ofSeconds(4).toNanos());
		client.restate(new RestateRequest(plan().changes()));
		clock.set(Duration.ofSeconds(5).toNanos());
		client.restate(new RestateRequest(plan().changes()));
		server.verify();
	}

	@Test
	void failedHealthIsNotCached() {
		server.expect(requestTo("http://ai.test:8100/health"))
			.andRespond(withSuccess("{\"ready\":false}", MediaType.APPLICATION_JSON));
		ready();
		expectRestate();
		assertThatThrownBy(() -> client.restate(new RestateRequest(plan().changes()))).isInstanceOf(BusinessException.class);
		assertThat(client.restate(new RestateRequest(plan().changes())).summary()).isEqualTo("요약");
		server.verify();
	}

	@Test
	void upstreamFailureInvalidatesRecentHealth() {
		ready();
		server.expect(requestTo("http://ai.test:8100/plan/restate")).andRespond(withServerError());
		ready();
		expectRestate();
		assertThatThrownBy(() -> client.restate(new RestateRequest(plan().changes()))).isInstanceOf(BusinessException.class);
		assertThat(client.restate(new RestateRequest(plan().changes())).summary()).isEqualTo("요약");
		server.verify();
	}

	private void expectRestate() {
		server.expect(requestTo("http://ai.test:8100/plan/restate"))
			.andRespond(withSuccess("{\"restatements\":[],\"summary\":\"요약\"}", MediaType.APPLICATION_JSON));
	}

	@Test
	void missingAddressDoesNotCallLocalhost() {
		assertThatThrownBy(() -> new AiPatchClient(new AiProperties(null, null, null)).health())
			.isInstanceOf(BusinessException.class);
	}

	private void ready() {
		server.expect(requestTo("http://ai.test:8100/health"))
			.andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));
	}

	private PlanSlots plan() {
		return new PlanSlots(List.of(new PlanChange("modify", "increase", "enemy", "Axebot",
			"health", "20%", List.of("hard"), "체력 증가")), List.of("RPG"));
	}

	private CaseInput candidate() {
		return new CaseInput(GID, "게임", "", List.of("RPG"),
			List.of(new CaseChange("modify", "increase", "enemy", "증가")), null, null);
	}
}
