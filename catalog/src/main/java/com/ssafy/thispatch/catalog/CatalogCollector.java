package com.ssafy.thispatch.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

/**
 * 스팀 전체 카탈로그를 받아 서비스 DB 에 넣는다.
 *
 * <pre>
 *   java -jar thispatch-catalog.jar            전체 (약 186콜 · 2분)
 *   java -jar thispatch-catalog.jar --pages 2  앞 2페이지만 (확인용)
 * </pre>
 *
 * <p>환경변수로 접속 정보를 받는다. 값을 코드나 저장소에 넣지 않는다.
 * <pre>
 *   DB_URL       jdbc:postgresql://127.0.0.1:15432/thispatch   (SSH 터널)
 *   DB_USER      thispatch
 *   DB_PASSWORD  서버1 infra/.env 의 POSTGRES_PASSWORD
 * </pre>
 *
 * <p>매일 전량 순회한다. 증분하지 않는다 — 전체가 2분인데 증분 로직을 붙여
 * 얻는 게 없고, 정작 매일 바뀌는 것은 기존 게임의 리뷰 수·긍정률이라
 * 「새 appid 만」 받으면 그쪽을 통째로 놓친다. (문서 결정 2026-09-07)
 */
public final class CatalogCollector {

    public static void main(String[] args) throws Exception {
        int maxPages = intArg(args, "--pages", Integer.MAX_VALUE);

        String url = env("DB_URL");
        String user = env("DB_USER");
        String password = env("DB_PASSWORD");

        SteamCatalogClient steam = new SteamCatalogClient();
        long began = System.currentTimeMillis();

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        // 18만 행을 넣는다. 재작성(rewriteBatchedInserts)은 UPSERT 에 안 먹으므로
        // 배치 크기로만 줄인다 — 페이지(1000행)마다 한 번씩 보낸다.
        props.setProperty("ApplicationName", "thispatch-catalog");

        try (Connection conn = DriverManager.getConnection(url, props);
             CatalogStore store = new CatalogStore(conn)) {

            say("① 태그 이름표");
            JsonNode tagJson = steam.fetchTags();
            List<CatalogStore.Tag> tags = CatalogMapper.toTags(tagJson);
            int tagRows = store.upsertTags(tags);
            System.out.printf("   %d 개%n", tagRows);
            // 예전 실행에서 들어간 태그도 아는 것으로 친다.
            store.loadKnownTagIds();

            say("② 카탈로그");
            int start = 0;
            int page = 0;
            int total = -1;
            long games = 0;
            long links = 0;
            long skippedTags = 0;
            long received = 0;
            long dropped = 0;

            while (page < maxPages) {
                JsonNode res = steam.fetchPage(start);
                if (total < 0) {
                    total = res.path("metadata").path("total_matching_records").asInt(0);
                    System.out.printf("   전체 %,d 개 · 예상 %d 페이지%n",
                            total, (total + SteamCatalogClient.PAGE_SIZE - 1) / SteamCatalogClient.PAGE_SIZE);
                }

                JsonNode items = res.path("store_items");
                if (!items.isArray() || items.isEmpty()) {
                    break;
                }

                List<CatalogStore.Game> batch = CatalogMapper.toGames(items);
                received += items.size();
                dropped += CatalogMapper.lastDropped();
                games += store.upsertGames(batch);
                links += store.insertGameTags(batch);
                skippedTags += store.lastSkippedTags();

                page++;
                start += SteamCatalogClient.PAGE_SIZE;
                if (page % 20 == 0) {
                    System.out.printf("   %d 페이지 · 게임 %,d · 태그연결 %,d%n", page, games, links);
                }

                // 받은 개수가 한 페이지보다 적으면 마지막 페이지다.
                if (items.size() < SteamCatalogClient.PAGE_SIZE) {
                    break;
                }
            }

            say("③ 결과");
            System.out.printf("   페이지        %d%n", page);
            System.out.printf("   스팀이 준 것  %,d  (전체라고 말한 수 %,d)%n", received, total);
            if (dropped > 0) {
                // name 이 NOT NULL 이다. 스팀이 이름 없이 내려주는 항목은
                // 넣을 값이 없어 버린다. 몇 개인지는 남긴다.
                System.out.printf("   버림          %,d  (이름이 없는 항목)%n", dropped);
            }
            System.out.printf("   게임 저장     %,d%n", games);
            System.out.printf("   태그 연결     %,d%n", links);
            if (skippedTags > 0) {
                // populartags 는 인기 태그 429개만 준다. 그 밖의 태그는
                // 이름을 몰라 tag 테이블에 없고, 외래키 때문에 넣을 수 없다.
                System.out.printf("   건너뜀        %,d (이름을 모르는 태그)%n", skippedTags);
            }
            System.out.printf("   DB game       %,d 행%n", store.countGames());
            System.out.printf("   DB game_tag   %,d 행%n", store.countGameTags());
            System.out.printf("   걸린 시간     %.1f 초%n", (System.currentTimeMillis() - began) / 1000.0);
        }
    }

    private static void say(String s) {
        System.out.println();
        System.out.println(s);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 없습니다.");
        }
        return v;
    }

    private static int intArg(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }
}
