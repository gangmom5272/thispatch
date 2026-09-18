package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.substring;

import com.ssafy.thispatch.common.NewsLake;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/**
 * 공지(news_raw)를 서비스 DB 의 {@code news} 표에 넣는다.
 *
 * <pre>
 *   DB_URL=jdbc:postgresql://127.0.0.1:15432/thispatch \
 *   DB_USER=thispatch DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.NewsToPostgres ... thispatch-spark.jar [--dry-run]
 * </pre>
 *
 * <h2>무엇을 넣는가</h2>
 *
 * <p>같은 gid 가 여러 번 수집됐으면 가장 최근 것({@link NewsLake#latest})만. 판정(is_patch ·
 * patch_reason)이 Parquet 에 있으면 그 값으로, 없으면 {@code false · 0:unjudged} 자리값으로 넣되
 * 이미 있는 행의 판정은 건드리지 않는다. gid 가 PK 라 {@code ON CONFLICT (gid)} 로 갱신한다.
 *
 * <p>개발사 공지(feedname = steam_community_announcements)만 넣는다 — 성현님 결정 (2026-09-18).
 * 뉴스 사이트 RSS · 스팀 스토어 공지는 패치노트 분석 대상이 아니다. 변환({@link NewsToParquet})이
 * 이미 빼지만, 그 전에 만들어진 파티션이 남아 있을 수 있어 여기서도 <b>같은 기준</b>
 * ({@link NewsToParquet#excluded()})으로 한 번 더 빼고 몇 건인지 찍는다.
 * ⚠ is_external_url 로 가르지 않는다 — 스팀은 개발사 공지도 true 로 준다.
 *
 * <h2>왜 DB 는 드라이버만 만지는가</h2>
 *
 * <p>{@link ReviewStatsToPostgres} 와 같다. 서비스 DB 는 마스터의 SSH 터널(127.0.0.1)로만
 * 닿고, executor 는 워커 노트북에서 돌아 거기서 127.0.0.1 은 자기 자신이다. 예전 판은
 * {@code spark.read().jdbc()} 와 {@code mapPartitions} 안에서 연결을 열었는데 그건 executor
 * 에서 도는 코드라 이 클러스터에서는 <i>Connection refused</i> 가 난다 (2026-09-17 실측).
 * 그래서 game 목록은 드라이버가 읽어 작은 DataFrame 으로 만들고, 결과는
 * {@code toLocalIterator()} 로 드라이버가 받아 연결 하나 · 트랜잭션 하나로 넣는다.
 * 넣는 중 실패하면 롤백되어 표는 시작 전 그대로다.
 */
public final class NewsToPostgres {

    /** 한 번에 DB 로 보내는 행 수. 본문(contents)이 크다 — 너무 크게 잡지 않는다. */
    private static final int BATCH = 500;

    /** 판정이 아직 없을 때 patch_reason 에 남기는 표시. */
    static final String UNJUDGED = "0:unjudged";

    private static final int TITLE_MAX = 500;
    private static final int URL_MAX = 1_000;
    private static final int FEED_TAGS_MAX = 300;

    private NewsToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = args.length > 0 && "--dry-run".equals(args[0].trim());

        String url = env("DB_URL");
        String user = env("DB_USER");
        String password = env("DB_PASSWORD");

        SparkSession spark = SparkSessions.build("news-to-postgres");
        try {
            // 같은 gid 가 여러 번 수집됐으면 가장 최근 것만 쓴다.
            Dataset<Row> lake = NewsLake.latest(spark).persist(StorageLevel.DISK_ONLY());
            try {
                long total = lake.count();
                System.out.println("news_raw   " + total + "건 (gid 기준 최신본)");
                if (total == 0) {
                    System.out.println("넣을 것이 없다.");
                    return;
                }

                boolean judged = hasJudgment(lake);
                System.out.println(judged
                        ? "판정  있음 — is_patch · patch_reason 을 Parquet 값으로 갱신한다"
                        : "판정  없음 — is_patch=false · patch_reason=" + UNJUDGED
                          + " 로 넣고, 이미 있는 행의 판정은 건드리지 않는다");

                // 개발사 공지만 넣는다 (클래스 주석 · 성현님 결정). 변환이 같은 기준으로 이미 뺐다면 0건이다.
                long excludedRows = lake.filter(NewsToParquet.excluded()).count();
                System.out.println("개발사 공지 유지  " + (total - excludedRows) + "건 · 제외 " + excludedRows
                        + "건  (feedname != " + NewsToParquet.DEVELOPER_FEED + ")");
                if (excludedRows > 0) {
                    System.out.println("⚠ 제외 건수가 0 이 아니다 — 옛 기준으로 변환된 파티션이 남아 있다. news-convert --all 을 다시 돌릴 것");
                }
                Dataset<Row> shaped = shape(lake.filter(NewsToParquet.excluded().equalTo(false)));

                // ⚠ published_ts 가 없으면 넣을 수 없다. news.published_ts 는 NOT NULL 이고
                //   게시일을 지어낼 수는 없다. 몇 건인지 찍고 뺀다.
                long noDate = shaped.filter(col("published_ts").isNull()).count();
                if (noDate > 0) {
                    System.out.println("⚠ 게시일이 없어 빼는 것  " + noDate + "건");
                }
                shaped = shaped.filter(col("published_ts").isNotNull());

                // game 에 있는 appid 만 넣을 수 있다 (fk_news_game). 목록은 드라이버가 읽는다.
                List<Long> gameIds = new ArrayList<>(readLongs(url, user, password, "SELECT appid FROM game"));
                Dataset<Row> games = spark.createDataset(gameIds, Encoders.LONG()).toDF("game_appid");
                System.out.println("game      " + gameIds.size() + "개 (드라이버가 읽음)");
                long before = shaped.count();
                Dataset<Row> kept = shaped.join(games, shaped.col("appid").equalTo(games.col("game_appid")), "left_semi")
                        .persist(StorageLevel.DISK_ONLY());
                try {
                    long keptRows = kept.count();
                    if (before != keptRows) {
                        System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + (before - keptRows) + "건");
                    }
                    System.out.println("넣을 것   " + keptRows + "건");
                    System.out.println("  패치 판정 true  " + kept.filter(col("is_patch").equalTo(true)).count() + "건");
                    if (dryRun) {
                        System.out.println("--dry-run 이라 쓰지 않는다.");
                        kept.show(5, 60);
                        return;
                    }

                    long inDbBefore = count(url, user, password, "news");
                    long written = upsert(kept, judged, url, user, password);
                    long inDb = count(url, user, password, "news");
                    System.out.println("넣었다    " + written + "건 · DB news " + inDbBefore + " → " + inDb + "행");
                    if (inDb < keptRows) {
                        throw new IllegalStateException("DB 행 수가 넣은 것보다 적다: " + inDb + " < " + keptRows);
                    }
                } finally {
                    kept.unpersist();
                }
            } finally {
                lake.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    static boolean hasJudgment(Dataset<Row> lake) {
        return java.util.Arrays.asList(lake.columns()).contains("is_patch");
    }

    static Dataset<Row> shape(Dataset<Row> lake) {
        // 판정이 없으면 자리값을 만든다. is_patch 가 NOT NULL 이라 무언가는 넣어야 하고,
        // '아직 안 봄' 이라는 사실은 patch_reason 에만 남길 수 있다.
        org.apache.spark.sql.Column isPatch = hasJudgment(lake)
                ? coalesce(col("is_patch"), lit(false))
                : lit(false);
        org.apache.spark.sql.Column reason = hasJudgment(lake)
                ? col("patch_reason")
                : lit(UNJUDGED);

        return lake.select(
                col("gid"),
                col("appid"),
                // title · contents 는 DB 가 NOT NULL 이다. 빈 문자열로 채워서 행을 살린다 —
                // 제목이 없다고 공지를 버리면 패치 판별이 볼 것이 줄어든다.
                substring(coalesce(col("title"), lit("")), 1, TITLE_MAX).as("title"),
                coalesce(col("contents"), lit("")).as("contents"),
                substring(col("url"), 1, URL_MAX).as("url"),
                col("published_ts"),
                substring(col("feed_tags"), 1, FEED_TAGS_MAX).as("feed_tags"),
                col("collected_ts"),
                isPatch.as("is_patch"),
                reason.as("patch_reason"));
    }

    /**
     * 드라이버의 연결 하나, 트랜잭션 하나로 전부 넣는다(UPSERT).
     *
     * <p>{@code toLocalIterator()} 는 파티션을 하나씩 드라이버로 가져오므로 본문 전체를
     * 한 번에 메모리에 올리지 않는다. 도중에 실패하면 롤백 — 표는 시작 전 그대로다.
     */
    private static long upsert(Dataset<Row> rows, boolean judged,
                               String url, String user, String password) {
        String judgedUpdate = judged
                ? """
                  ,
                      is_patch     = EXCLUDED.is_patch,
                      patch_reason = EXCLUDED.patch_reason"""
                : "";

        String sql = """
                INSERT INTO news (gid, appid, title, contents, url,
                                  published_ts, feed_tags, collected_at,
                                  is_patch, patch_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (gid) DO UPDATE SET
                    appid        = EXCLUDED.appid,
                    title        = EXCLUDED.title,
                    contents     = EXCLUDED.contents,
                    url          = EXCLUDED.url,
                    published_ts = EXCLUDED.published_ts,
                    feed_tags    = EXCLUDED.feed_tags,
                    collected_at = EXCLUDED.collected_at""" + judgedUpdate + "\n";

        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int inBatch = 0;
                java.util.Iterator<Row> it = rows.toLocalIterator();
                while (it.hasNext()) {
                    Row r = it.next();
                    ps.setString(1, r.getAs("gid"));
                    ps.setLong(2, r.<Long>getAs("appid"));
                    ps.setString(3, r.getAs("title"));
                    ps.setString(4, r.getAs("contents"));
                    ps.setString(5, r.getAs("url"));
                    ps.setTimestamp(6, new Timestamp(r.<Long>getAs("published_ts") * 1000L));
                    ps.setString(7, r.getAs("feed_tags"));
                    ps.setTimestamp(8, new Timestamp(r.<Long>getAs("collected_ts") * 1000L));
                    ps.setBoolean(9, Boolean.TRUE.equals(r.<Boolean>getAs("is_patch")));
                    ps.setString(10, r.getAs("patch_reason"));
                    ps.addBatch();
                    if (++inBatch >= BATCH) {
                        n += ps.executeBatch().length;
                        inBatch = 0;
                        if (n % 100_000 == 0) {
                            System.out.println("  … " + n + "건");
                        }
                    }
                }
                if (inBatch > 0) {
                    n += ps.executeBatch().length;
                }
                conn.commit();
                System.out.println("넣었다(UPSERT) news — 한 트랜잭션");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("news 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    private static long count(String url, String user, String password, String table) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(table + " count 실패: " + e.getMessage(), e);
        }
    }

    private static Set<Long> readLongs(String url, String user, String password, String sql) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            Set<Long> out = new java.util.HashSet<>();
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("조회 실패 (" + sql + "): " + e.getMessage(), e);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("환경변수 " + name + " 이 없습니다.");
        }
        return v;
    }
}
