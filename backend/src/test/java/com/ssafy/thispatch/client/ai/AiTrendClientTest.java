package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssafy.thispatch.global.exception.BusinessException;

class AiTrendClientTest {

	private static final String RESPONSE = """
		{"appid":7,"title":"반응 추세","summary":"기간 요약입니다.","facts":[{"key":"positive_pct","value":"66.7%"}],
		 "patch_effects":[],"caveats":["인과관계는 아닙니다."],"used_llm":true,"clean":true,"elapsed_ms":4200}
		""";
	private MockRestServiceServer server;
	private AiTrendClient client;

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("http://ai.test:8100");
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient rest = builder.build();
		client = new AiTrendClient(rest, rest, true);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void sendsStatisticsAndAcceptsTemplateWhenModelIsNotReady(boolean ready) {
		health(ready);
		server.expect(requestTo("http://ai.test:8100/trends/summarize")).andExpect(method(HttpMethod.POST))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.appid").value(7))
			.andExpect(jsonPath("$.window_days").value(7)).andExpect(jsonPath("$.use_llm").value(true))
			.andExpect(jsonPath("$.daily[0].date").value("2026-01-01"))
			.andExpect(jsonPath("$.daily[0].reviews").value(30)).andExpect(jsonPath("$.daily[0].positive").value(20))
			.andExpect(jsonPath("$.daily[0].first_reviews").value(20)).andExpect(jsonPath("$.daily[0].first_positive").value(15))
			.andExpect(jsonPath("$.daily[0].edited_reviews").value(10)).andExpect(jsonPath("$.daily[0].edited_positive").value(5))
			.andExpect(jsonPath("$.patches[0].date").value("2026-01-01"))
			.andExpect(jsonPath("$.patches[0].gid").value("18446744073709551615"))
			.andRespond(withSuccess(ready ? RESPONSE : RESPONSE.replace("true", "false"), MediaType.APPLICATION_JSON));
		var result = client.summarize(request());
		assertThat(result.summary()).isEqualTo("기간 요약입니다.");
		assertThat(result.caveats()).containsExactly("인과관계는 아닙니다.");
		assertThat(result.usedLlm()).isEqualTo(ready);
		assertThat(result.clean()).isEqualTo(ready);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"wrongGame", "missingFlags", "uncleanLlm", "missingCaveats", "nullCaveat", "blankSummary", "malformed", "empty"})
	void rejectsMalformedOrUnsafeResponses(String problem) {
		health(true);
		String response = switch (problem) {
			case "wrongGame" -> RESPONSE.replace("\"appid\":7", "\"appid\":8");
			case "missingFlags" -> RESPONSE.replace("\"used_llm\":true,", "");
			case "uncleanLlm" -> RESPONSE.replace("\"clean\":true", "\"clean\":false");
			case "missingCaveats" -> RESPONSE.replace("\"caveats\":[\"인과관계는 아닙니다.\"],", "");
			case "nullCaveat" -> RESPONSE.replace("[\"인과관계는 아닙니다.\"]", "[null]");
			case "blankSummary" -> RESPONSE.replace("기간 요약입니다.", " ");
			case "empty" -> "";
			default -> "not json";
		};
		server.expect(requestTo("http://ai.test:8100/trends/summarize"))
			.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
		assertUnavailable();
		server.verify();
	}

	@Test
	void tooManyPatchesDoesNotSendTruncatedOrInvalidRequest() {
		var original = request();
		var oversized = new AiTrendClient.Request(7, original.daily(),
			Collections.nCopies(51, original.patches().get(0)), 7, true);
		assertThatThrownBy(() -> client.summarize(oversized)).isInstanceOf(BusinessException.class)
			.hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage());
		server.verify();
	}

	@Test
	void fiftyPatchesAreSentWithoutDroppingAny() {
		health(true);
		server.expect(requestTo("http://ai.test:8100/trends/summarize"))
			.andExpect(jsonPath("$.patches.length()").value(50))
			.andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
		var original = request();
		client.summarize(new AiTrendClient.Request(7, original.daily(),
			Collections.nCopies(50, original.patches().get(0)), 7, true));
		server.verify();
	}

	@Test
	void timeoutDoesNotRetry() {
		health(true);
		server.expect(requestTo("http://ai.test:8100/trends/summarize"))
			.andRespond(withException(new SocketTimeoutException("internal address")));
		assertUnavailable();
		server.verify();
	}

	@Test
	void serverErrorIsUnavailable() {
		health(true);
		server.expect(requestTo("http://ai.test:8100/trends/summarize"))
			.andRespond(withServerError().body("private details"));
		assertUnavailable();
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 422})
	void invalidBackendPayloadIsNotTreatedAsUnavailable(int status) {
		health(true);
		server.expect(requestTo("http://ai.test:8100/trends/summarize"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).body("{\"code\":\"VALIDATION_FAILED\",\"date\":\"2026-01-01\"}"));
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(IllegalStateException.class)
			.hasMessage("AI rejected backend trend statistics");
		server.verify();
	}

	@Test
	void missingHealthFlagDoesNotRequestSummary() {
		server.expect(requestTo("http://ai.test:8100/health")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		assertUnavailable();
		server.verify();
	}

	@Test
	void missingAddressDoesNotCallLocalhost() {
		client = new AiTrendClient(new AiProperties(null, null, null));
		assertUnavailable();
		server.verify();
	}

	private void health(boolean ready) {
		server.expect(requestTo("http://ai.test:8100/health")).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"ready\":" + ready + "}", MediaType.APPLICATION_JSON));
	}

	private void assertUnavailable() {
		assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(BusinessException.class)
			.hasMessage(AiErrorCode.AI_UNAVAILABLE.getMessage());
	}

	private AiTrendClient.Request request() {
		LocalDate date = LocalDate.of(2026, 1, 1);
		return new AiTrendClient.Request(7, List.of(new AiTrendClient.Day(date, 30, 20, 20, 15, 10, 5)),
			List.of(new AiTrendClient.Patch(date, "패치", "18446744073709551615")), 7, true);
	}
}
