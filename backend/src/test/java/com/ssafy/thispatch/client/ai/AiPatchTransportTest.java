package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ssafy.thispatch.client.ai.AiPatchContracts.PlanChange;
import com.ssafy.thispatch.client.ai.AiPatchContracts.RestateRequest;

class AiPatchTransportTest {

    @Test
    void sendsJsonBodyWithoutHttp2Upgrade() throws Exception {
        var requestBody = new AtomicReference<String>();
        var upgradeHeader = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String response;
            if (exchange.getRequestURI().getPath().equals("/health")) {
                response = "{\"ready\":true}";
            } else {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                response = "{\"restatements\":[\"체력 증가\"],\"summary\":\"체력 증가\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var client = new AiPatchClient(new AiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), null, null));
            var change = new PlanChange("modify", "increase", "enemy", "액스봇", "체력",
                "20%", List.of(), "적 액스봇의 체력을 20% 증가시킵니다.");

            assertThat(client.restate(new RestateRequest(List.of(change))).summary()).isEqualTo("체력 증가");
            assertThat(upgradeHeader.get()).as("AI 서버가 읽을 수 있는 HTTP/1.1 요청이어야 한다").isNull();
            var json = new ObjectMapper().readTree(requestBody.get());
            assertThat(json.path("changes").get(0).path("target").asText()).isEqualTo("액스봇");
            assertThat(json.path("changes").get(0).path("change_type").asText()).isEqualTo("modify");
        } finally {
            server.stop(0);
        }
    }
}
