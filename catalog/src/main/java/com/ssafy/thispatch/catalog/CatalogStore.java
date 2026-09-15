package com.ssafy.thispatch.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서비스 DB(서버1)에 카탈로그를 넣는다. SSH 터널로 붙는다.
 *
 * <p>넣는 순서가 정해져 있다 — {@code game_tag} 가 {@code game} 과 {@code tag} 를
 * 둘 다 참조한다.
 *
 * <pre>
 *   tag  →  game  →  game_tag
 * </pre>
 */
public class CatalogStore implements AutoCloseable {

    private final Connection conn;

    /**
     * ⚠ 카탈로그가 주는 tagid 가 tag 테이블에 다 있지는 않다.
     *   populartags 는 인기 태그 약 429개만 주는데, 게임에는 그 밖의 태그도
     *   붙어 있다. 그대로 넣으면 외래키 위반으로 페이지 전체가 롤백된다.
     *   그래서 아는 태그만 골라 넣는다.
     */
    private final Set<Integer> knownTagIds = new HashSet<>();

    public CatalogStore(Connection conn) throws SQLException {
        this.conn = conn;
        this.conn.setAutoCommit(false);
    }

    /** 태그 이름표를 넣고, 아는 태그 목록을 기억한다. */
    public int upsertTags(List<Tag> tags) throws SQLException {
        String sql = """
                INSERT INTO tag (tag_id, name_ko, collected_at)
                VALUES (?, ?, ?)
                ON CONFLICT (tag_id) DO UPDATE
                SET name_ko = EXCLUDED.name_ko,
                    collected_at = EXCLUDED.collected_at""";
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Tag t : tags) {
                ps.setInt(1, t.tagId());
                ps.setString(2, cut(t.name(), 100));
                ps.setTimestamp(3, now);
                ps.addBatch();
                knownTagIds.add(t.tagId());
            }
            int n = ps.executeBatch().length;
            conn.commit();
            return n;
        }
    }

    /** 이미 DB 에 있는 태그까지 아는 것으로 친다 (태그 수집을 건너뛰고 돌릴 때). */
    public void loadKnownTagIds() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT tag_id FROM tag");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                knownTagIds.add(rs.getInt(1));
            }
        }
    }

    /**
     * 게임 한 페이지(최대 1000개)를 넣는다. 페이지 단위로 커밋한다.
     *
     * <p>⚠ 지우지 않는다. {@code TRUNCATE} 후 재삽입은 절대 아니다 —
     * 리뷰·공지·집계가 {@code appid} 로 물려 있어 외래키가 깨진다.
     * 응답에 없는 게임은 그대로 두고 갱신만 멈춘다 (문서 결정 2026-09-07).
     */
    public int upsertGames(List<Game> games) throws SQLException {
        String sql = """
                INSERT INTO game (appid, name, developer, publisher, short_description,
                                  store_url_path, release_ts, is_early_access, is_coming_soon,
                                  store_review_count, store_positive_pct, capsule_path, collected_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (appid) DO UPDATE
                SET name = EXCLUDED.name,
                    developer = EXCLUDED.developer,
                    publisher = EXCLUDED.publisher,
                    short_description = EXCLUDED.short_description,
                    store_url_path = EXCLUDED.store_url_path,
                    release_ts = EXCLUDED.release_ts,
                    is_early_access = EXCLUDED.is_early_access,
                    is_coming_soon = EXCLUDED.is_coming_soon,
                    store_review_count = EXCLUDED.store_review_count,
                    store_positive_pct = EXCLUDED.store_positive_pct,
                    capsule_path = EXCLUDED.capsule_path,
                    collected_at = EXCLUDED.collected_at""";
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Game g : games) {
                ps.setLong(1, g.appid());
                ps.setString(2, cut(g.name(), 500));
                setNullable(ps, 3, cut(g.developer(), 500));
                setNullable(ps, 4, cut(g.publisher(), 500));
                setNullable(ps, 5, g.shortDescription());
                setNullable(ps, 6, cut(g.storeUrlPath(), 300));
                if (g.releaseTs() == null) {
                    ps.setNull(7, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(7, Timestamp.from(g.releaseTs()));
                }
                setNullableBool(ps, 8, g.isEarlyAccess());
                setNullableBool(ps, 9, g.isComingSoon());
                setNullableInt(ps, 10, g.reviewCount());
                setNullableInt(ps, 11, g.positivePct());
                setNullable(ps, 12, cut(g.capsulePath(), 200));
                ps.setTimestamp(13, now);
                ps.addBatch();
            }
            int n = ps.executeBatch().length;
            conn.commit();
            return n;
        }
    }

    /**
     * 게임-태그 연결. 이미 있으면 그냥 둔다.
     *
     * <p>문서 결정(2026-09-07) — {@code game_tag} 는 최초 수집 때만 저장하고
     * 이후 수정하지 않는다. UPSERT 로는 빠진 태그를 지울 수 없어 복잡해지는데,
     * 장르 태그는 잘 바뀌지 않으므로 최초 값으로 충분하다.
     *
     * <p>{@code DO NOTHING} 이라 지워지지 않고, 태그가 비어 있던 게임은
     * 다음 실행에서 채워진다. 출시 직후 게임은 태그가 아직 안 붙어 있어서
     * 그때 저장하면 영원히 빈 상태로 남기 때문이다.
     */
    public int insertGameTags(List<Game> games) throws SQLException {
        String sql = """
                INSERT INTO game_tag (appid, tag_id, weight)
                VALUES (?,?,?)
                ON CONFLICT (appid, tag_id) DO NOTHING""";
        int skipped = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Game g : games) {
                for (GameTag t : g.tags()) {
                    if (!knownTagIds.contains(t.tagId())) {
                        skipped++;
                        continue;
                    }
                    ps.setLong(1, g.appid());
                    ps.setInt(2, t.tagId());
                    ps.setInt(3, t.weight());
                    ps.addBatch();
                }
            }
            int n = ps.executeBatch().length;
            conn.commit();
            lastSkippedTags = skipped;
            return n;
        }
    }

    private int lastSkippedTags;

    /** 직전 {@link #insertGameTags} 에서 이름을 몰라 건너뛴 태그 수. */
    public int lastSkippedTags() {
        return lastSkippedTags;
    }

    public long countGames() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM game");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public long countGameTags() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM game_tag");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    // ── 작은 도우미들 ──────────────────────────────────────

    /** 컬럼 길이를 넘으면 DB 가 통째로 거부한다. 넘치면 자른다. */
    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void setNullable(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null || v.isBlank()) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i, v);
        }
    }

    private static void setNullableBool(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BOOLEAN);
        } else {
            ps.setBoolean(i, v);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    // ── 담아 나르는 것들 ───────────────────────────────────

    public record Tag(int tagId, String name) {}

    public record GameTag(int tagId, int weight) {}

    public record Game(long appid, String name, String developer, String publisher,
                       String shortDescription, String storeUrlPath, Instant releaseTs,
                       Boolean isEarlyAccess, Boolean isComingSoon, Integer reviewCount,
                       Integer positivePct, String capsulePath, List<GameTag> tags) {}
}
