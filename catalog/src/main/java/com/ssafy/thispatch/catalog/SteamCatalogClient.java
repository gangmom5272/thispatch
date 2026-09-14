package com.ssafy.thispatch.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * 스팀 카탈로그 · 태그 API 호출.
 *
 * <p>둘 다 API 키가 필요 없다. 키가 필요한 것은 {@code ISteamUser/GetPlayerSummaries}
 * 하나인데 쓰지 않기로 했다.
 *
 * <p>⚠ {@code ISteamApps/GetAppList} 는 쓸 수 없다. 스팀이 내렸다 — 2026-09-14 에
 * 확인했을 때 404 를 돌려주고, 키 없이 보이는 메서드 목록에도 없다.
 * 전체 카탈로그는 아래 {@code IStoreQueryService/Query} 로 받는다.
 */
public class SteamCatalogClient {

    /** 한 번에 받을 수 있는 최대치. 더 크게 보내도 1000 으로 잘린다. */
    public static final int PAGE_SIZE = 1000;

    private static final String CATALOG =
            "https://api.steampowered.com/IStoreQueryService/Query/v1/?input_json=";
    private static final String TAGS =
            "https://store.steampowered.com/tagdata/populartags/";

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;

    public SteamCatalogClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), Duration.ofSeconds(60));
    }

    SteamCatalogClient(HttpClient http, ObjectMapper json, Duration timeout) {
        this.http = Objects.requireNonNull(http);
        this.json = Objects.requireNonNull(json);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * 카탈로그 한 페이지.
     *
     * @param start 몇 번째부터. 0 → 1000 → 2000 으로 넘긴다
     * @return {@code response} 노드. {@code metadata.total_matching_records} 와
     *         {@code store_items} 가 들어 있다
     */
    public JsonNode fetchPage(int start) throws IOException, InterruptedException {
        // 요청 본문이 없는 GET 이다. JSON 을 URL 인코딩해 input_json 에 넣는다.
        //
        // language=koreana 로 이름과 설명을 한국어로 받는다. 한국어가 없는
        // 게임은 스팀이 알아서 영어를 준다.
        //
        // ⚠ sort 는 반드시 '안정적인' 기준이어야 한다. 2 를 쓴다.
        //
        //   start=N 은 "N 번째부터" 라는 뜻이다. 줄 세우는 순서가 부를 때마다
        //   흔들리면 같은 자리에 매번 다른 게임이 온다. 어떤 게임은 여러 번
        //   받고 어떤 게임은 한 번도 못 받는다.
        //
        //   인기순(11)이 그렇다. 12만 등 아래는 리뷰가 0개라 인기도가 전부
        //   같아서 스팀이 매번 다른 순서로 돌려준다. 깊은 페이지를 연속 두 번
        //   불렀을 때 겹치는 게임이 1000개 중 0개였다. 그 결과 18.5만 개 중
        //   13.1만 개만 들어오고 5.4만 개(29%)를 놓쳤다.
        //
        //   sort 0·3~10·12 도 같은 문제가 있다. 안정적인 것은 1 과 2 뿐이고
        //   둘 다 누락 0% 이며 2 가 더 빠르다 (195초 대 255초).
        //   (2026-09-14 실측)
        String body = """
                {"query":{"start":%d,"count":%d,"sort":2,\
                "filters":{"type_filters":{"include_games":true}}},\
                "context":{"language":"koreana","country_code":"KR","steam_realm":1},\
                "data_request":{"include_tag_count":10,"include_basic_info":true,\
                "include_release":true,"include_reviews":true,"include_assets":true}}"""
                .formatted(start, PAGE_SIZE);

        String url = CATALOG + URLEncoder.encode(body, StandardCharsets.UTF_8);
        return get(url).path("response");
    }

    /**
     * 태그 이름표. {@code [{"tagid":492,"name":"인디"}, ...]} 형태로 약 429개.
     *
     * <p>카탈로그 API 는 {@code tagid} 만 주고 이름을 주지 않는다. 그런데
     * {@code game_tag.tag_id} 가 {@code tag} 를 참조하므로 이것을 먼저 채워야 한다.
     */
    public JsonNode fetchTags() throws IOException, InterruptedException {
        return get(TAGS + "koreana");
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("스팀이 HTTP " + res.statusCode() + " 를 돌려줬습니다: " + url);
        }
        return json.readTree(res.body());
    }
}
