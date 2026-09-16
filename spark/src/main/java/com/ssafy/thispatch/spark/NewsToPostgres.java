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
import java.sql.Timestamp;
import java.util.Properties;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * HDFS 의 {@code /news_raw} 를 서비스 DB 의 {@code news} 테이블에 넣는다.
 *
 * <pre>
 *   /news_raw/dt=*&#47;   ->   thispatch.news
 * </pre>
 *
 * <p>실행
 *
 * <pre>
 *   DB_URL=jdbc:postgresql://127.0.0.1:15432/thispatch \
 *   DB_USER=thispatch DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.NewsToPostgres thispatch-spark.jar
 *
 *   ... thispatch-spark.jar --dry-run     무엇을 넣을지만 세어 보고 쓰지는 않는다
 * </pre>
 *
 * <p>서비스 DB 는 서버1 에 있고 SSH 터널(15432)로 닿는다. 마스터에서 돌린다.
 *
 * <h2>왜 리뷰는 안 넣고 공지만 넣는가</h2>
 *
 * 리뷰는 1.47억 건이라 서버1(4코어 16GB)에 원본을 넣을 수 없다. 리뷰는 Spark 로
 * 집계해서 <b>집계 결과만</b> 넣는다(S15P21A202-174 → -25). 공지는 11만 건 남짓이라
 * 원문을 그대로 넣어도 된다. 그리고 넣어야 한다 — 패치 판별(S15P21A202-130)과
 * 패치 상세 API 가 이 테이블을 본다.
 *
 * <h2>⚠ is_patch 를 덮어쓰지 않는다</h2>
 *
 * {@code news.is_patch} 는 NOT NULL 인데 {@code NewsSchema} 에는 그 칼럼이 없다.
 * 일부러 없다 — 무엇이 패치인지는 규칙으로 가리는 일이고(S15P21A202-130),
 * 규칙이 바뀔 때마다 공지를 다시 받을 수는 없기 때문이다.
 *
 * 그래서 여기서는
 * <ul>
 *   <li>처음 넣을 때만 {@code false} 를 넣는다 — '아직 판별 안 함' 의 자리값이다</li>
 *   <li>이미 있는 행에는 {@code is_patch} · {@code patch_reason} 을 <b>건드리지 않는다</b></li>
 * </ul>
 *
 * 이것을 안 지키면 이 잡을 한 번 더 돌릴 때마다 판별 결과가 전부 지워진다.
 *
 * <h2>⚠ game 에 없는 appid 는 빼고 넣는다</h2>
 *
 * {@code news.appid} 가 {@code game} 을 참조한다(fk_news_game). 없는 appid 가 한 건이라도
 * 섞이면 그 배치가 통째로 롤백된다. 미리 걸러 내고, 몇 건을 걸렀는지 찍는다.
 */
public final class NewsToPostgres {

    /** 한 번에 DB 로 보내는 행 수. */
    private static final int BATCH = 1_000;

    /** news 테이블의 길이 제한. 넘으면 자른다 — 넣지 못하는 것보다 낫다. */
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
            Dataset<Row> lake = NewsLake.latest(spark);
            long total = lake.count();
            System.out.println("news_raw   " + total + "건 (gid 기준 최신본)");
            if (total == 0) {
                System.out.println("넣을 것이 없다.");
                return;
            }

            Dataset<Row> shaped = shape(lake);

            // ⚠ published_ts 가 없으면 넣을 수 없다. news.published_ts 는 NOT NULL 이고
            //   게시일을 지어낼 수는 없다. 몇 건인지 찍고 뺀다.
            long noDate = shaped.filter(col("published_ts").isNull()).count();
            if (noDate > 0) {
                System.out.println("⚠ 게시일이 없어 빼는 것  " + noDate + "건");
            }
            shaped = shaped.filter(col("published_ts").isNotNull());

            // game 에 있는 appid 만 남긴다.
            Dataset<Row> games = spark.read()
                    .jdbc(url, "(SELECT appid FROM game) g", props(user, password))
                    .select(col("appid").as("game_appid"));
            long before = shaped.count();
            shaped = shaped.join(games, shaped.col("appid").equalTo(games.col("game_appid")), "left_semi");
            long kept = shaped.count();
            if (before != kept) {
                System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + (before - kept) + "건");
            }

            System.out.println("넣을 것   " + kept + "건");
            if (dryRun) {
                System.out.println("--dry-run 이라 쓰지 않는다.");
                shaped.show(5, 60);
                return;
            }

            long written = upsert(shaped, url, user, password);
            System.out.println("넣었다    " + written + "건");
        } finally {
            spark.stop();
        }
    }

    /** Parquet 칼럼을 news 테이블 모양으로 맞춘다. */
    static Dataset<Row> shape(Dataset<Row> lake) {
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
                col("collected_ts"));
    }

    /**
     * gid 를 키로 UPSERT 한다.
     *
     * <p>⚠ {@code is_patch} 와 {@code patch_reason} 은 UPDATE 목록에 없다.
     * 처음 넣을 때만 자리값이 들어가고, 그 뒤로는 S15P21A202-130 이 채운 값이 남는다.
     */
    private static long upsert(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO news (gid, appid, title, contents, url,
                                  published_ts, feed_tags, collected_at,
                                  is_patch, patch_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, NULL)
                ON CONFLICT (gid) DO UPDATE SET
                    appid        = EXCLUDED.appid,
                    title        = EXCLUDED.title,
                    contents     = EXCLUDED.contents,
                    url          = EXCLUDED.url,
                    published_ts = EXCLUDED.published_ts,
                    feed_tags    = EXCLUDED.feed_tags,
                    collected_at = EXCLUDED.collected_at
                """;

        return rows.toJavaRDD().mapPartitions(part -> {
            long n = 0;
            if (!part.hasNext()) {
                return java.util.Collections.singletonList(0L).iterator();
            }
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int inBatch = 0;
                    while (part.hasNext()) {
                        Row r = part.next();
                        ps.setString(1, r.getAs("gid"));
                        ps.setLong(2, r.<Long>getAs("appid"));
                        ps.setString(3, r.getAs("title"));
                        ps.setString(4, r.getAs("contents"));
                        ps.setString(5, r.getAs("url"));
                        ps.setTimestamp(6, new Timestamp(r.<Long>getAs("published_ts") * 1000L));
                        ps.setString(7, r.getAs("feed_tags"));
                        ps.setTimestamp(8, new Timestamp(r.<Long>getAs("collected_ts") * 1000L));
                        ps.addBatch();
                        if (++inBatch >= BATCH) {
                            n += ps.executeBatch().length;
                            conn.commit();
                            inBatch = 0;
                        }
                    }
                    if (inBatch > 0) {
                        n += ps.executeBatch().length;
                        conn.commit();
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("news 적재 실패: " + e.getMessage(), e);
            }
            return java.util.Collections.singletonList(n).iterator();
        }).reduce(Long::sum);
    }

    private static Properties props(String user, String password) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("driver", "org.postgresql.Driver");
        return p;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("환경변수 " + name + " 이 없습니다.");
        }
        return v;
    }
}
