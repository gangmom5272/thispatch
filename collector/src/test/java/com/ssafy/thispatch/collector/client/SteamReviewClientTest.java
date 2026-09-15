package com.ssafy.thispatch.collector.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

class SteamReviewClientTest {

    private final LinkedBlockingQueue<URI> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private SteamReviewClient client;
    private volatile int status;
    private volatile String body;
    private volatile String retryAfter;

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        body = "{\"success\":1,\"reviews\":[],\"cursor\":\"next\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/appreviews/", exchange -> {
            requests.add(exchange.getRequestURI());
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = new SteamReviewClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/appreviews/"),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void requestsAllReviewsInUpdatedOrderAndPreservesRawFields() throws Exception {
        body = """
                {"success":1,"cursor":"next+/=雪","query_summary":{"num_reviews":2},"reviews":[
                  {"recommendationid":"123","review":"한글\\n본문 🎮","weighted_vote_score":"0.1",
                   "author":{"steamid":"76561198000000000","future_field":true},
                   "unknown":{"values":[1,null,"x"]}},
                  {"recommendationid":"124","weighted_vote_score":0.123456789012345678901}
                ]}
                """;

        SteamReviewPage page = client.fetchPage(730L, SteamReviewClient.FIRST_CURSOR);

        URI request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/appreviews/730", request.getPath());
        assertEquals(Map.of("json", "1", "filter", "updated", "language", "all",
                "review_type", "all", "purchase_type", "all", "num_per_page", "100",
                "filter_offtopic_activity", "0", "cursor", "*"), query(request));
        assertEquals("next+/=雪", page.nextCursor());
        assertEquals(2, page.reviews().size());
        var first = page.reviews().get(0);
        assertEquals("123", first.get("recommendationid").textValue());
        assertEquals("한글\n본문 🎮", first.get("review").textValue());
        assertEquals("0.1", first.get("weighted_vote_score").textValue());
        assertTrue(first.path("author").path("future_field").booleanValue());
        assertTrue(first.path("unknown").path("values").get(1).isNull());
        assertEquals(new BigDecimal("0.123456789012345678901"),
                page.reviews().get(1).get("weighted_vote_score").decimalValue());
        assertFalse(first.has("appid"), "수집 메타데이터는 저장 단계에서 추가한다");
    }

    @Test
    void roundTripsOpaqueCursorWithoutAddingQueryParameters() throws Exception {
        String cursor = "Ao+/= ?&filter=recent#%雪";
        assertEquals("next", client.fetchPage(730L, cursor).nextCursor());
        URI request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(cursor, query(request).get("cursor"));
        assertEquals("updated", query(request).get("filter"));
        assertEquals(8, query(request).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":1,\"reviews\":[]}",
            "{\"success\":1,\"reviews\":[],\"cursor\":null}",
            "{\"success\":1,\"reviews\":[],\"cursor\":\"\"}"
    })
    void returnsEmptyPageWithoutDecidingCollectionIsFinished(String response) throws Exception {
        body = response;
        var page = client.fetchPage(730L, "previous");
        assertTrue(page.reviews().isEmpty());
        assertNull(page.nextCursor());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429, 500, 503, 302})
    void exposesHttpStatusAndRetryAfterWithoutRetrying(int httpStatus) {
        status = httpStatus;
        retryAfter = "3600";
        body = "<html>blocked</html>";
        var failure = assertThrows(SteamReviewException.class, () -> client.fetchPage(730L, "*"));
        assertEquals(SteamReviewException.Kind.HTTP_ERROR, failure.kind());
        assertEquals(httpStatus, failure.httpStatus());
        assertEquals("3600", failure.retryAfter());
        assertEquals(1, requests.size());
        assertFalse(failure.getMessage().contains(body));
    }

    @Test
    void preservesHttpDateRetryAfter() {
        status = 429;
        retryAfter = "Wed, 16 Sep 2026 09:00:00 GMT";
        var failure = assertThrows(SteamReviewException.class, () -> client.fetchPage(730L, "*"));
        assertEquals(retryAfter, failure.retryAfter());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void distinguishesApiFailureFromEmptyPage(int success) {
        body = "{\"success\":" + success + "}";
        var failure = assertThrows(SteamReviewException.class, () -> client.fetchPage(730L, "*"));
        assertEquals(SteamReviewException.Kind.API_FAILURE, failure.kind());
        assertEquals(200, failure.httpStatus());
        assertNull(failure.retryAfter());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html>blocked</html>", "", "null", "[]", "{}",
            "{\"success\":\"1\",\"reviews\":[]}",
            "{\"success\":1}", "{\"success\":1,\"reviews\":{}}",
            "{\"success\":1,\"reviews\":[null],\"cursor\":\"next\"}",
            "{\"success\":1,\"reviews\":[{}]}",
            "{\"success\":1,\"reviews\":[],\"cursor\":123}",
            "{\"success\":1,\"reviews\":[]} {}"
    })
    void rejectsMalformedResponsesInsteadOfTreatingThemAsEmpty(String response) {
        body = response;
        var failure = assertThrows(SteamReviewException.class, () -> client.fetchPage(730L, "*"));
        assertEquals(SteamReviewException.Kind.INVALID_RESPONSE, failure.kind());
    }

    @Test
    void rejectsInvalidArgumentsBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> client.fetchPage(0L, "*"));
        assertThrows(IllegalArgumentException.class, () -> client.fetchPage(730L, null));
        assertThrows(IllegalArgumentException.class, () -> client.fetchPage(730L, " "));
        assertTrue(requests.isEmpty());
    }

    @Test
    void appliesRequestTimeoutAndPropagatesTransportFailure() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        var timeout = new HttpTimeoutException("request timed out");
        when(transport.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(timeout);
        client = new SteamReviewClient(transport, new ObjectMapper(),
                URI.create("https://store.steampowered.com/appreviews/"), Duration.ofMillis(250));

        assertSame(timeout, assertThrows(HttpTimeoutException.class, () -> client.fetchPage(730L, "*")));
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(1)).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(Duration.ofMillis(250), request.getValue().timeout().orElseThrow());
    }

    @Test
    void propagatesInterruptionWithoutRetrying() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        var interruption = new InterruptedException("stop collection");
        when(transport.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(interruption);
        client = new SteamReviewClient(transport, new ObjectMapper(),
                URI.create("https://store.steampowered.com/appreviews/"), Duration.ofSeconds(1));
        assertSame(interruption, assertThrows(InterruptedException.class, () -> client.fetchPage(730L, "*")));
        verify(transport, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
