package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class AiSummaryTransportTest {

	private final AtomicReference<String> requestBody = new AtomicReference<>();
	private final AtomicReference<String> upgradeHeader = new AtomicReference<>();
	private final AtomicReference<String> requestMethod = new AtomicReference<>();
	private final AtomicReference<String> requestPath = new AtomicReference<>();
	private final AtomicReference<String> contentType = new AtomicReference<>();
	private HttpServer server;
	private AiProperties properties;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String response = "{\"ready\":true}";
			if (!path.equals("/health")) {
				requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
				requestMethod.set(exchange.getRequestMethod());
				requestPath.set(path);
				contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				response = path.equals("/trends/summarize")
					? """
						{"appid":7,"summary":"추세 요약","caveats":[],"used_llm":false,"clean":false}
						"""
					: """
						{"appid":7,"scope_type":"ALL","scope_key":"","summary":"리뷰 요약",
						 "phrases":[],"evidence_ids":[1,2,3],"review_count":3,"clean":true}
						""";
			}
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		});
		server.start();
		properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort(), null, null);
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void sendsTrendJsonBodyWithoutHttp2Upgrade() throws Exception {
		LocalDate date = LocalDate.of(2026, 9, 20);
		var request = new AiTrendClient.Request(7,
			List.of(new AiTrendClient.Day(date, 30, 20, 25, 18, 5, 2)),
			List.of(new AiTrendClient.Patch(date, "밸런스 패치", "123")), 7, false);

		assertThat(new AiTrendClient(properties).summarize(request).summary()).isEqualTo("추세 요약");

		JsonNode json = receivedJson("/trends/summarize");
		assertThat(json.path("daily").get(0).path("date").asText()).isEqualTo("2026-09-20");
		assertThat(json.path("daily").get(0).path("first_reviews").asInt()).isEqualTo(25);
		assertThat(json.path("patches").get(0).path("title").asText()).isEqualTo("밸런스 패치");
		assertThat(json.path("window_days").asInt()).isEqualTo(7);
		assertThat(json.path("use_llm").booleanValue()).isFalse();
	}

	@Test
	void sendsReviewJsonBodyWithoutHttp2Upgrade() throws Exception {
		var reviews = List.of(
			new AiReviewClient.Review(1, "전투가 재미있어요", true, 5, "korean", 90),
			new AiReviewClient.Review(2, "밸런스가 좋아졌어요", true, 4, "korean", 120),
			new AiReviewClient.Review(3, "버그가 남아 있어요", false, 3, "korean", 150));
		var request = new AiReviewClient.SummaryRequest(7, "게임", "ALL", "", reviews);

		assertThat(new AiReviewClient(properties).summarize(request).summary()).isEqualTo("리뷰 요약");

		JsonNode json = receivedJson("/reviews/summarize");
		assertThat(json.path("scope_type").asText()).isEqualTo("ALL");
		assertThat(json.path("reviews").size()).isEqualTo(3);
		assertThat(json.path("reviews").get(0).path("review_text").asText()).isEqualTo("전투가 재미있어요");
		assertThat(json.path("reviews").get(0).path("review_id").asInt()).isEqualTo(1);
	}

	private JsonNode receivedJson(String expectedPath) throws Exception {
		assertThat(requestMethod.get()).isEqualTo("POST");
		assertThat(requestPath.get()).isEqualTo(expectedPath);
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(upgradeHeader.get()).as("AI 서버가 본문을 읽도록 HTTP/2 전환을 시도하지 않는다").isNull();
		assertThat(requestBody.get()).isNotBlank();
		JsonNode json = new ObjectMapper().readTree(requestBody.get());
		assertThat(json.path("appid").asLong()).isEqualTo(7);
		return json;
	}
}
