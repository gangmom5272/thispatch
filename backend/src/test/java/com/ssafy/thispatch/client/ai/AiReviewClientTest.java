package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssafy.thispatch.client.ai.AiReviewClient.Review;
import com.ssafy.thispatch.client.ai.AiReviewClient.SummaryRequest;
import com.ssafy.thispatch.global.exception.BusinessException;

class AiReviewClientTest {

	private MockRestServiceServer server;
	private AiReviewClient client;
	private static final String VALID_RESPONSE = """
		{"appid":7,"scope_type":"BAND","scope_key":"2","title":"반응","summary":"리뷰 요약입니다.",
		 "phrases":["전투"],"evidence_ids":[1,2],"review_count":3,"model":"qwen",
		 "attempts":1,"clean":true,"elapsed_ms":1234}
		""";

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("http://ai.test:8100");
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient rest = builder.build();
		client = new AiReviewClient(rest, rest, true);
	}

	@Test
	void sendsSnakeCaseContractAndReadsResponseAfterReadinessCheck() {
		ready();
		server.expect(requestTo("http://ai.test:8100/reviews/summarize"))
			.andExpect(method(HttpMethod.POST)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.appid").value(7)).andExpect(jsonPath("$.scope_type").value("BAND"))
			.andExpect(jsonPath("$.scope_key").value("2"))
			.andExpect(jsonPath("$.reviews[0].review_id").value(1))
			.andExpect(jsonPath("$.reviews[0].review_text").value("리뷰"))
			.andExpect(jsonPath("$.reviews[0].playtime_at_review").value(90))
			.andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));
		var result = client.summarize(request());
		assertThat(result.summary()).isEqualTo("리뷰 요약입니다.");
		assertThat(result.evidenceIds()).containsExactly(1L, 2L);
		server.verify();
	}

	@Test
	void warmingServerDoesNotReceiveInferenceRequest() {
		server.expect(requestTo("http://ai.test:8100/health"))
			.andRespond(withSuccess("{\"ready\":false}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(BusinessException.class);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"unclean", "wrongGame", "wrongScope", "foreignEvidence", "wrongCount", "empty", "malformed"})
	void rejectsUnusableResponse(String problem) {
		ready();
		String response = switch (problem) {
			case "unclean" -> VALID_RESPONSE.replace("\"clean\":true", "\"clean\":false");
			case "wrongGame" -> VALID_RESPONSE.replace("\"appid\":7", "\"appid\":8");
			case "wrongScope" -> VALID_RESPONSE.replace("\"scope_key\":\"2\"", "\"scope_key\":\"3\"");
			case "foreignEvidence" -> VALID_RESPONSE.replace("[1,2]", "[999]");
			case "wrongCount" -> VALID_RESPONSE.replace("\"review_count\":3", "\"review_count\":4");
			case "empty" -> "";
			default -> "not json";
		};
		server.expect(requestTo("http://ai.test:8100/reviews/summarize"))
			.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(BusinessException.class)
			.hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage())
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
				.isEqualTo(AiErrorCode.AI_UNAVAILABLE));
		server.verify();
	}

	@Test
	void timeoutIsNotRetried() {
		ready();
		server.expect(requestTo("http://ai.test:8100/reviews/summarize"))
			.andRespond(withException(new SocketTimeoutException("private server details")));
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(BusinessException.class)
			.hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage());
		server.verify();
	}

	@Test
	void upstreamErrorDoesNotLeakResponseBody() {
		ready();
		server.expect(requestTo("http://ai.test:8100/reviews/summarize"))
			.andRespond(withBadRequest().body("private request details"));
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(BusinessException.class)
			.hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage());
		server.verify();
	}

	@Test
	void missingAddressDoesNotFallBackToLocalhost() {
		var unconfigured = new AiReviewClient(new AiProperties(null, null, null));
		assertThatThrownBy(unconfigured::health).isInstanceOf(BusinessException.class);
	}

	@Test
	void validatesServerAddressAndTimeouts() {
		assertThatThrownBy(() -> new AiProperties("file:///tmp/ai", null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AiProperties("http://ai:8100", Duration.ZERO, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new AiProperties("http://ai:8100", null, null).readTimeout()).isEqualTo(Duration.ofSeconds(30));
	}

	private void ready() {
		server.expect(requestTo("http://ai.test:8100/health")).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"ready\":true,\"embedder_loaded\":true,\"qwen_loaded\":true}", MediaType.APPLICATION_JSON));
	}

	private SummaryRequest request() {
		return new SummaryRequest(7, "게임", "BAND", "2", IntStream.rangeClosed(1, 3)
			.mapToObj(id -> new Review(id, "리뷰", true, 50, "korean", 90)).toList());
	}
}
