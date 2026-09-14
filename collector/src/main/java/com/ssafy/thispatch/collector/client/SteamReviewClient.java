package com.ssafy.thispatch.collector.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Steam appreviews를 수정 시각 순으로 한 페이지 조회한다.
 * 호출은 워커에서 하며, 반복 수집·대기·재시작·저장은 호출자가 관리한다.
 *
 * @see <a href="https://partner.steamgames.com/doc/store/getreviews">Steam 리뷰 API</a>
 */
public final class SteamReviewClient {

    public static final String FIRST_CURSOR = "*";

    private final HttpClient httpClient;
    private final ObjectReader jsonReader;
    private final URI baseUri;
    private final Duration requestTimeout;

    public SteamReviewClient() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(), URI.create("https://store.steampowered.com/appreviews/"),
                Duration.ofSeconds(30));
    }

    /** HttpClient의 연결 제한 시간과 요청 전체 제한 시간을 각각 설정할 수 있다. */
    public SteamReviewClient(HttpClient httpClient, ObjectMapper objectMapper,
                             URI baseUri, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.jsonReader = Objects.requireNonNull(objectMapper).reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (!("https".equalsIgnoreCase(baseUri.getScheme())
                || "http".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || !baseUri.getPath().endsWith("/")
                || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null
                || baseUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) directory URI");
        }
    }

    /**
     * cursor는 첫 호출에 {@link #FIRST_CURSOR}, 이후에는 응답의 원문을 그대로 전달한다.
     * HTTP 오류를 빈 페이지로 바꾸거나 자동으로 재시도하지 않는다.
     * 인터럽트는 호출자에게 전달해 배치 중단을 보장한다.
     */
    public SteamReviewPage fetchPage(long appid, String cursor) throws IOException, InterruptedException {
        if (appid <= 0) {
            throw new IllegalArgumentException("appid must be positive");
        }
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank; use FIRST_CURSOR initially");
        }

        String query = "?json=1&filter=updated&language=all&review_type=all"
                + "&purchase_type=all&num_per_page=100&filter_offtopic_activity=0&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(appid + query))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Dispatch-Collector/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (status != 200) {
            throw new SteamReviewException(SteamReviewException.Kind.HTTP_ERROR, status, retryAfter,
                    "Steam reviews returned HTTP " + status);
        }

        JsonNode body;
        try {
            body = jsonReader.readTree(response.body());
        } catch (JsonProcessingException e) {
            // 오류 메시지와 로그에 리뷰 원문 또는 HTML 차단 페이지를 복사하지 않는다.
            throw invalidResponse(status, retryAfter, "response is not a single JSON document");
        }
        if (body == null || !body.isObject()) {
            throw invalidResponse(status, retryAfter, "response must be an object");
        }
        JsonNode success = body.get("success");
        if (success == null || !success.isIntegralNumber()) {
            throw invalidResponse(status, retryAfter, "success must be an integer");
        }
        if (!success.canConvertToInt() || success.intValue() != 1) {
            throw new SteamReviewException(SteamReviewException.Kind.API_FAILURE, status, retryAfter,
                    "Steam reviews reported an unsuccessful response");
        }

        JsonNode reviews = body.get("reviews");
        if (reviews == null || !reviews.isArray()) {
            throw invalidResponse(status, retryAfter, "reviews must be an array");
        }
        List<ObjectNode> items = new ArrayList<>(reviews.size());
        for (JsonNode review : reviews) {
            if (!review.isObject()) {
                throw invalidResponse(status, retryAfter, "each review must be an object");
            }
            items.add((ObjectNode) review);
        }

        JsonNode cursorNode = body.get("cursor");
        if (cursorNode != null && !cursorNode.isNull() && !cursorNode.isTextual()) {
            throw invalidResponse(status, retryAfter, "cursor must be a string");
        }
        String nextCursor = cursorNode == null || cursorNode.isNull() ? null : cursorNode.textValue();
        if (nextCursor != null && nextCursor.isBlank()) {
            nextCursor = null;
        }
        if (!items.isEmpty() && nextCursor == null) {
            throw invalidResponse(status, retryAfter, "non-empty page must have a cursor");
        }
        return new SteamReviewPage(items, nextCursor);
    }

    private static SteamReviewException invalidResponse(int status, String retryAfter, String reason) {
        return new SteamReviewException(SteamReviewException.Kind.INVALID_RESPONSE, status, retryAfter,
                "Invalid Steam reviews response: " + reason);
    }
}
