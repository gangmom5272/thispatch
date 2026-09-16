package com.ssafy.thispatch.collector.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 게임 하나의 스팀 공지(뉴스)를 가져온다. 패치노트가 여기에 섞여 온다.
 *
 * <p><b>리뷰와 다른 점.</b> 리뷰는 커서로 페이지를 넘기며 수십만 건을 받지만, 공지는
 * <b>호출 한 번으로 끝</b>이다. 게임 하나당 한 번이면 된다.
 *
 * <pre>
 *   리뷰   게임 11.7만 개 x 페이지 수백~수만  ->  1.5억 건 · 하루
 *   공지   게임 11.7만 개 x 1회              ->  약 80분
 * </pre>
 *
 * <p>키가 필요 없다(노션 「데이터 정리」 — 7개 중 6개가 키 없이 된다).
 *
 * <p><b>어느 것이 패치노트인지 가리지 않는다.</b> 받은 그대로 넘긴다. 판별은
 * {@code S15P21A202-130} 이 규칙으로 한다. 여기서 걸러 버리면 규칙을 고칠 때마다
 * 11.7만 개를 다시 받아야 한다.
 *
 * @see <a href="https://partner.steamgames.com/doc/webapi/ISteamNews">ISteamNews</a>
 */
public final class SteamNewsClient {

    /**
     * 몇 건을 달라고 할지의 기본값. 전량이다.
     *
     * <p>⚠ {@code count} 에 상한이 없다. 크게 줘도 있는 만큼만 온다 —
     * 2026-09-15 실측으로 CS2(총 1,756건)에 10000 을 줘도 1,756건이 왔다.
     * 그래도 <b>호출은 게임당 한 번</b>이라 시간이 거의 안 늘어난다.
     *
     * <pre>
     *   count=100    0.15 MB · 0.36초     (CS2)
     *   count=2000   4.28 MB · 0.82초
     * </pre>
     *
     * <p>게임 25개 표본으로 게임당 평균 9.8건 · 19.7KB 였다. 11.7만 개면
     * 약 2.2GB(gzip 0.4GB)라, 리뷰 30GB 에 비하면 부담이 없다.
     */
    public static final int DEFAULT_COUNT = 10_000;

    /**
     * 본문 길이 제한. 0 이면 자르지 않는다.
     *
     * <p>⚠ 0 으로 둔다. 기본값이 아니다 — 스팀은 {@code maxlength} 를 주면 본문을
     * 그 길이에서 <b>끊고 "..." 를 붙인다.</b> 패치노트는 본문이 전부라서 끊으면
     * 분석이 불가능해진다.
     */
    public static final int NO_TRUNCATION = 0;

    private final HttpClient httpClient;
    private final ObjectReader jsonReader;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final int count;

    public SteamNewsClient() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                URI.create("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"),
                Duration.ofSeconds(30), DEFAULT_COUNT);
    }

    public SteamNewsClient(HttpClient httpClient, ObjectMapper objectMapper,
                           URI baseUri, Duration requestTimeout, int count) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.jsonReader = Objects.requireNonNull(objectMapper).reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
        this.count = count;
        if (!("https".equalsIgnoreCase(baseUri.getScheme())
                || "http".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null
                || baseUri.getRawFragment() != null
                || baseUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) URI");
        }
    }

    /**
     * 공지를 최신순으로 가져온다. 공지가 없는 게임이면 빈 목록이다.
     *
     * <p>HTTP 오류를 빈 목록으로 바꾸지 않는다. 재시도도 하지 않는다 —
     * 호출자가 백오프와 함께 다룬다. 인터럽트는 그대로 올려보내 배치가 멈출 수 있게 한다.
     */
    public List<ObjectNode> fetch(long appid) throws IOException, InterruptedException {
        return fetch(appid, count);
    }

    /**
     * 몇 건을 달라고 할지 이번 호출에만 정한다.
     *
     * <p>증분 수집은 작게 준다 — 지난 배치 이후에 올라온 것만 필요하고, 게임 하나가
     * 하루에 공지를 수십 개 올릴 일은 없다. 받는 양이 줄어 저장할 것도 준다.
     */
    public List<ObjectNode> fetch(long appid, int howMany) throws IOException, InterruptedException {
        if (appid <= 0) {
            throw new IllegalArgumentException("appid must be positive");
        }
        if (howMany < 1) {
            throw new IllegalArgumentException("howMany must be >= 1");
        }

        String query = "?appid=" + appid
                + "&count=" + howMany
                + "&maxlength=" + NO_TRUNCATION
                + "&format=json";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri + query))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Thispatch-Collector/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);

        JsonNode body = tryParse(response.body());

        // ⚠ 403 이 두 가지를 뜻한다. 가르지 않으면 치명적이다.
        //
        //   (1) 그런 게임이 없다  — 본문이 JSON "{}" 다
        //   (2) 스팀이 막았다     — 본문이 HTML 차단 페이지다
        //
        //   2026-09-15 실측: 없는 appid(999999999)를 2초 간격으로 5번 불렀더니
        //   다섯 번 모두 「403 · 본문 {}」 이었다. 실재하는 게임은 200 이었다.
        //
        //   이걸 전부 (2)로 보면 상장폐지된 게임 하나마다 조각이 1시간씩 멈춘다.
        //   카탈로그 18.5만 개에는 그런 게임이 섞여 있다.
        //
        //   반대로 전부 (1)로 보면 진짜 차단당했을 때 조용히 건너뛴다. 그래서
        //   JSON 으로 읽히는지로 가른다 — 차단 페이지는 HTML 이라 파싱에 실패한다.
        //   많은 게임이 한꺼번에 '공지 없음' 으로 나오면 수집 로그의 집계에 드러난다.
        if (status == 403 && body != null && body.isObject() && !body.has("appnews")) {
            return List.of();
        }
        if (status != 200) {
            throw new SteamNewsException(SteamNewsException.Kind.HTTP_ERROR, status, retryAfter,
                    "Steam news returned HTTP " + status);
        }
        if (body == null) {
            // ⚠ 오류 메시지에 응답 원문을 넣지 않는다. 차단 페이지가 통째로 로그에 남는다.
            throw invalid(status, retryAfter, "response is not a single JSON document");
        }
        if (!body.isObject()) {
            throw invalid(status, retryAfter, "response must be an object");
        }

        // { "appnews": { "appid": 730, "newsitems": [ ... ], "count": 1234 } }
        //
        // ⚠ appnews 가 없을 수 있다. 200 에 빈 객체를 주는 경우가 있다.
        //   오류가 아니라 '공지가 없는 게임' 으로 본다.
        JsonNode appnews = body.get("appnews");
        if (appnews == null || appnews.isNull()) {
            return List.of();
        }
        if (!appnews.isObject()) {
            throw invalid(status, retryAfter, "appnews must be an object");
        }
        JsonNode items = appnews.get("newsitems");
        if (items == null || items.isNull()) {
            return List.of();
        }
        if (!items.isArray()) {
            throw invalid(status, retryAfter, "newsitems must be an array");
        }

        List<ObjectNode> out = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw invalid(status, retryAfter, "each news item must be an object");
            }
            out.add((ObjectNode) item);
        }
        return out;
    }

    /** JSON 으로 읽히면 그 트리를, 아니면 null 을 준다. 차단 페이지는 HTML 이라 null 이 된다. */
    private JsonNode tryParse(String body) {
        try {
            return jsonReader.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static SteamNewsException invalid(int status, String retryAfter, String reason) {
        return new SteamNewsException(SteamNewsException.Kind.INVALID_RESPONSE, status, retryAfter,
                "Invalid Steam news response: " + reason);
    }
}
