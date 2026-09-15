package com.ssafy.thispatch.collector.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스팀 공지 응답을 어떻게 읽는지 본다.
 *
 * <p><b>403 을 가르는 규칙이 이 클래스의 핵심이다.</b> 2026-09-15 실측으로 스팀은
 * 없는 게임에도 403 을 준다 — 본문은 {@code {}} 다. 이걸 차단으로 오해하면
 * 상장폐지된 게임 하나마다 조각이 1시간씩 멈춘다.
 */
class SteamNewsClientTest {

    @SuppressWarnings("unchecked")
    private SteamNewsClient clientReturning(int status, String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        // ⚠ when(...).thenReturn 은 제네릭 추론이 막힌다. doReturn 을 쓴다.
        doReturn(response).when(http).send(any(), any());
        return new SteamNewsClient(http, new ObjectMapper(),
                URI.create("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"),
                Duration.ofSeconds(5), 100);
    }

    @Test
    @DisplayName("공지를 받아 목록으로 준다")
    void parsesNewsItems() throws Exception {
        SteamNewsClient client = clientReturning(200, """
                {"appnews":{"appid":730,"newsitems":[
                  {"gid":"1","title":"패치","contents":"[p]내용[/p]"},
                  {"gid":"2","title":"공지","contents":"본문"}],"count":2}}""");

        List<ObjectNode> items = client.fetch(730L);

        assertEquals(2, items.size());
        assertEquals("패치", items.get(0).path("title").asText());
    }

    @Test
    @DisplayName("없는 게임의 403 은 '공지 없음' 으로 본다 — 차단이 아니다")
    void treatsJsonForbiddenAsNoNews() throws Exception {
        // 2026-09-15 실측: appid 999999999 를 5번 불러 다섯 번 모두 403 · 본문 {}
        SteamNewsClient client = clientReturning(403, "{}");

        assertEquals(List.of(), client.fetch(999999999L));
    }

    @Test
    @DisplayName("차단 페이지의 403 은 오류로 올린다 — 조용히 건너뛰면 안 된다")
    void treatsHtmlForbiddenAsError() throws Exception {
        SteamNewsClient client = clientReturning(403,
                "<html><head><title>Forbidden</title></head><body>blocked</body></html>");

        SteamNewsException failure =
                assertThrows(SteamNewsException.class, () -> client.fetch(730L));
        assertEquals(403, failure.httpStatus());
    }

    @Test
    @DisplayName("appnews 가 없으면 공지 없는 게임으로 본다")
    void handlesMissingAppnews() throws Exception {
        assertEquals(List.of(), clientReturning(200, "{}").fetch(730L));
    }

    @Test
    @DisplayName("newsitems 가 없어도 빈 목록이다")
    void handlesMissingNewsItems() throws Exception {
        assertEquals(List.of(), clientReturning(200, "{\"appnews\":{\"appid\":730}}").fetch(730L));
    }

    @Test
    @DisplayName("500 은 그대로 오류다")
    void propagatesServerError() throws Exception {
        SteamNewsClient client = clientReturning(500, "{}");

        SteamNewsException failure =
                assertThrows(SteamNewsException.class, () -> client.fetch(730L));
        assertEquals(500, failure.httpStatus());
    }

    @Test
    @DisplayName("본문을 잘라 오지 않는다 — 패치노트는 본문이 전부다")
    void doesNotTruncate() {
        assertEquals(0, SteamNewsClient.NO_TRUNCATION);
    }

    @Test
    @DisplayName("appid 가 0 이하면 부르지 않는다")
    void rejectsBadAppid() throws Exception {
        SteamNewsClient client = clientReturning(200, "{}");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> client.fetch(0L))
                .getMessage().contains("appid"));
    }
}
