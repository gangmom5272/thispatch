package com.ssafy.thispatch.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** 스팀 응답 JSON 을 {@code game} 테이블 모양으로 옮긴다. */
final class CatalogMapper {

    private CatalogMapper() {
    }

    static List<CatalogStore.Tag> toTags(JsonNode arr) {
        List<CatalogStore.Tag> out = new ArrayList<>();
        for (JsonNode n : arr) {
            int id = n.path("tagid").asInt(-1);
            String name = n.path("name").asText(null);
            if (id > 0 && name != null && !name.isBlank()) {
                out.add(new CatalogStore.Tag(id, name));
            }
        }
        return out;
    }

    /** 직전 {@link #toGames} 에서 넣을 값이 없어 버린 항목 수. */
    private static int lastDropped;

    static int lastDropped() {
        return lastDropped;
    }

    static List<CatalogStore.Game> toGames(JsonNode storeItems) {
        List<CatalogStore.Game> out = new ArrayList<>();
        int dropped = 0;
        for (JsonNode it : storeItems) {
            CatalogStore.Game g = toGame(it);
            if (g != null) {
                out.add(g);
            } else {
                dropped++;
            }
        }
        // 조용히 버리면 나중에 "전체보다 몇 개 적네" 를 설명할 수 없다.
        // 스팀이 이름 없이 내려주는 항목이 0.014% 쯤 섞여 있다 (실측).
        lastDropped = dropped;
        return out;
    }

    private static CatalogStore.Game toGame(JsonNode it) {
        long appid = it.path("appid").asLong(0);
        String name = text(it, "name");
        // name 이 NOT NULL 이다. 이름 없는 항목은 스토어에서 내려갔거나
        // 아직 공개되지 않은 것이라 넣을 값이 없다.
        if (appid <= 0 || name == null) {
            return null;
        }

        JsonNode basic = it.path("basic_info");
        JsonNode release = it.path("release");
        // summary_filtered 가 화면에 보이는 그 숫자다.
        // summary_unfiltered 는 스팀이 걸러낸 것까지 포함해 더 크다.
        JsonNode reviews = it.path("reviews").path("summary_filtered");

        Instant releaseTs = null;
        long epoch = release.path("steam_release_date").asLong(0);
        if (epoch > 0) {
            releaseTs = Instant.ofEpochSecond(epoch);
        }

        return new CatalogStore.Game(
                appid,
                name,
                // 개발사·퍼블리셔는 배열이다. 8.2% 가 두 곳 이상이다.
                // 별도 테이블 대신 쉼표로 이어 한 컬럼에 넣기로 했다.
                joinNames(basic.path("developers")),
                joinNames(basic.path("publishers")),
                text(basic, "short_description"),
                text(it, "store_url_path"),
                releaseTs,
                bool(it, "is_early_access"),
                bool(release, "is_coming_soon"),
                intOrNull(reviews, "review_count"),
                intOrNull(reviews, "percent_positive"),
                text(it.path("assets"), "main_capsule"),
                toGameTags(it.path("tags")),
                toPlayModes(it.path("categories")));
    }

    /**
     * 플레이 모드 ID. 싱글 · 멀티 · 협동 · PvP 같은 것들.
     *
     * <p>⚠ {@code categories} 에는 묶음이 둘이다. 여기서 쓰는 것은 앞의 것뿐이다.
     *
     * <pre>
     *   supported_player_categoryids   플레이 모드      13종  ← 이것
     *   feature_categoryids            도전과제 · 창작마당 등  45종
     * </pre>
     *
     * 둘이 번호 공간을 나눠 써서 1~82 에 섞여 있다. 번호만 보고 고르면 안 된다.
     * (2026-09-16 카탈로그 185,640 개 전수 조사)
     *
     * <p>이름은 여기서 붙이지 않는다. {@code play_mode} 표가 갖고 있고,
     * 모르는 ID 는 {@link CatalogStore} 가 건너뛰면서 몇 개인지 알려 준다.
     */
    private static List<Integer> toPlayModes(JsonNode categories) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode n : categories.path("supported_player_categoryids")) {
            int id = n.asInt(-1);
            if (id > 0) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<CatalogStore.GameTag> toGameTags(JsonNode arr) {
        List<CatalogStore.GameTag> out = new ArrayList<>();
        for (JsonNode n : arr) {
            int id = n.path("tagid").asInt(-1);
            if (id > 0) {
                out.add(new CatalogStore.GameTag(id, n.path("weight").asInt(0)));
            }
        }
        return out;
    }

    private static String joinNames(JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        StringJoiner sj = new StringJoiner(", ");
        for (JsonNode n : arr) {
            String nm = text(n, "name");
            if (nm != null) {
                sj.add(nm);
            }
        }
        return sj.length() == 0 ? null : sj.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asBoolean();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asInt();
    }
}
