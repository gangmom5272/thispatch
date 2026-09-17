package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/**
 * 리뷰 집계를 서비스 DB 에 적재한다 — {@code daily_stat} · {@code language_stat}.
 *
 * <pre>
 *   DB_URL=jdbc:postgresql://127.0.0.1:15432/thispatch \
 *   DB_USER=thispatch DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.ReviewStatsToPostgres ... thispatch-spark.jar
 *
 *   ... thispatch-spark.jar --dry-run          세어만 보고 쓰지 않는다
 *   ... thispatch-spark.jar --only daily       daily_stat 만
 *   ... thispatch-spark.jar --only language    language_stat 만
 *   ... thispatch-spark.jar --path hdfs://.../review_raw/delta/dt=2026-09-17
 *                                              이 경로만 읽는다 (시험용 · 결과는 그 범위의 집계일 뿐이다)
 * </pre>
 *
 * <p>계산은 {@link DailyStatAggregator} · {@link LanguageStatAggregator} 가 한다.
 * 그 둘은 결과 칼럼을 표와 같은 이름으로 내놓으면서도 DB 를 모른다 — 일부러 그렇다.
 * 이 클래스는 그 결과를 받아 넣는 것만 한다. 계산 규칙을 여기서 바꾸지 않는다.
 *
 * <h2>왜 TRUNCATE 뒤 INSERT 인가</h2>
 *
 * <p>두 표에 (appid, stat_date) · (appid, language_code) 유니크가 없다. PK 는
 * BIGSERIAL 하나뿐이라 ON CONFLICT 로 갱신할 키가 없다. 그래서 통째로 비우고
 * 다시 넣는다. 집계는 매번 전체 이력에서 다시 계산되므로 이것이 맞기도 하다 —
 * 어제 값을 남겨 둘 이유가 없다.
 *
 * <h2>왜 DB 는 드라이버만 만지는가</h2>
 *
 * <p>서비스 DB 는 서버1 에 있고 마스터의 SSH 터널(127.0.0.1:15432)로만 닿는다.
 * executor 는 워커 노트북에서 돌기 때문에 거기서 127.0.0.1 은 자기 자신이다 —
 * JDBC 를 executor 에서 열면 <i>Connection refused</i> 가 난다 (2026-09-17 실측).
 * 터널을 마스터 LAN 주소로 열어도 워커에서는 마스터 방화벽에 막힌다.
 *
 * <p>그래서 집계(무거운 것)는 클러스터가 하고, DB 를 여는 일은 전부 드라이버가 한다.
 * <ul>
 *   <li>game 의 appid 목록은 드라이버가 읽어 작은 DataFrame 으로 만든다.
 *       18만 개면 몇 MB 라 Spark 가 브로드캐스트 조인으로 처리한다.</li>
 *   <li>집계 결과는 {@code toLocalIterator()} 로 파티션 하나씩 드라이버로 받아
 *       연결 하나로 넣는다. 결과는 입력의 몇천 분의 일이라 드라이버가 감당한다.</li>
 * </ul>
 *
 * <p>덤으로 TRUNCATE 와 INSERT 가 <b>한 트랜잭션</b>이 된다. 넣는 중에 실패하면
 * 롤백되어 이전 값이 그대로 남고, 읽는 쪽은 비어 있는 표를 볼 일이 없다.
 *
 * <p>⚠ {@link NewsToPostgres} 는 아직 executor 에서 JDBC 를 연다. 같은 이유로
 *   이 클러스터에서는 실패할 것이다. 공지 충돌이 풀린 뒤 같은 방식으로 고쳐야 한다.
 *
 * <h2>왜 언어를 걸러 넣는가</h2>
 *
 * <p>{@code language_stat.language_code} 는 {@code language} 표를 참조한다(FK).
 * 표에 없는 코드는 INSERT 가 통째로 실패한다. {@link LanguageStatAggregator} 는
 * 수집기가 준 코드를 그대로 내놓고 "표 소속 검사는 적재 단계가 한다" 고 적어 두었다.
 * 그래서 여기서 {@code language} 표를 읽어 없는 코드를 빼고, <b>몇 건을 뺐는지
 * 코드별로 찍는다</b>. 조용히 버리지 않는다 — 빠진 코드가 있으면 시드(V10)에 넣는다.
 */
public final class ReviewStatsToPostgres {

    /** 한 번에 DB 로 보내는 행 수. NewsToPostgres 와 같다. */
    private static final int BATCH = 1_000;

    private ReviewStatsToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        String only = null;
        String path = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case "--dry-run" -> dryRun = true;
                case "--only" -> only = (i + 1 < args.length) ? args[++i].trim() : null;
                case "--path" -> path = (i + 1 < args.length) ? args[++i].trim() : null;
                default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
            }
        }
        // ⚠ --path 로 일부만 읽어 실제 적재까지 하면 표에 그 일부의 집계만 남는다.
        //   시험은 --dry-run 과 함께 쓴다. 전체 적재는 경로를 주지 않는다.
        if (path != null && !dryRun) {
            throw new IllegalArgumentException("--path 는 --dry-run 과 함께만 쓴다 (일부 집계로 표를 덮어쓰지 않게)");
        }
        if (only != null && !only.equals("daily") && !only.equals("language")) {
            throw new IllegalArgumentException("--only 는 daily 또는 language");
        }

        String url = env("DB_URL");
        String user = env("DB_USER");
        String password = env("DB_PASSWORD");
        Instant now = Instant.now();

        SparkSession spark = SparkSessions.build("review-stats-to-postgres");
        try {
            List<String> paths = path != null
                    ? existing(spark, new String[] {path})
                    : existing(spark, HdfsPaths.reviewAll());
            if (paths.isEmpty()) {
                System.out.println("읽을 것이 없다: " + String.join(", ", HdfsPaths.reviewAll()));
                return;
            }
            System.out.println("읽는 곳   " + String.join(", ", paths));

            // 두 집계가 같은 입력을 두 번 훑는다. 22GB 를 두 번 읽지 않게 붙잡아 둔다.
            Dataset<Row> reviews = spark.read().parquet(paths.toArray(String[]::new))
                    .persist(StorageLevel.MEMORY_AND_DISK());
            try {
                long total = reviews.count();
                System.out.println("리뷰 행   " + total + "건 (중복 포함 · 집계가 최신본만 고른다)");

                // game 에 있는 appid 만 넣을 수 있다 (fk_*_game).
                //
                // ⚠ appid 18만 개를 isin(...) 리터럴로 넣지 않는다. 실행계획 문자열이 수 MB 로
                //   부풀어 로그와 예외 메시지를 덮고, 계획 수립도 느려진다 (2026-09-17 실측).
                // ⚠ spark.read().jdbc() 도 쓰지 않는다. 그 스캔은 executor 에서 돌아 터널에
                //   못 닿는다 (클래스 주석 참고). 드라이버가 읽어 DataFrame 으로 만든다.
                List<Long> gameIds = new ArrayList<>(readLongs(url, user, password, "SELECT appid FROM game"));
                Dataset<Row> games = spark.createDataset(gameIds, Encoders.LONG())
                        .toDF("game_appid")
                        .persist(StorageLevel.MEMORY_AND_DISK());
                System.out.println("game      " + games.count() + "개 (드라이버가 읽음)");

                try {
                    if (only == null || only.equals("daily")) {
                        loadDaily(reviews, games, now, dryRun, url, user, password);
                    }
                    if (only == null || only.equals("language")) {
                        Set<String> languages = readStrings(url, user, password,
                                "SELECT language_code FROM language");
                        System.out.println("language  " + languages.size() + "개 코드");
                        loadLanguage(reviews, games, languages, now, dryRun, url, user, password);
                    }
                } finally {
                    games.unpersist();
                }
            } finally {
                reviews.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    // ── daily_stat ───────────────────────────────────────────────

    private static void loadDaily(Dataset<Row> reviews, Dataset<Row> games, Instant now, boolean dryRun,
                                  String url, String user, String password) {
        System.out.println();
        System.out.println("== daily_stat ==");
        Dataset<Row> stat;
        try {
            stat = DailyStatAggregator.aggregate(reviews, now).persist(StorageLevel.MEMORY_AND_DISK());
        } catch (IllegalArgumentException e) {
            // Aggregator 가 같은 시각에 다른 관측을 만나면 멈춘다. 지어내지 않는 것이 맞다.
            throw new IllegalStateException("daily_stat 집계 거부: " + e.getMessage(), e);
        }
        try {
            long rows = stat.count();
            Dataset<Row> kept = inGame(stat, games);
            long keptRows = kept.count();
            System.out.println("집계 행   " + rows + "건");
            if (rows != keptRows) {
                System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + (rows - keptRows) + "건");
            }
            System.out.println("넣을 것   " + keptRows + "건");
            kept.orderBy(col("appid"), col("stat_date")).show(5, false);
            if (dryRun) {
                System.out.println("--dry-run 이라 쓰지 않는다.");
                return;
            }
            long written = replaceDaily(kept, url, user, password);
            long inDb = count(url, user, password, "daily_stat");
            System.out.println("넣었다    " + written + "건 · DB daily_stat " + inDb + "행");
            if (written != inDb) {
                throw new IllegalStateException("넣은 수와 DB 행 수가 다르다: " + written + " vs " + inDb);
            }
        } finally {
            stat.unpersist();
        }
    }

    /** daily_stat 을 비우고 다시 넣는다 — 한 트랜잭션. 실패하면 이전 값이 그대로 남는다. */
    private static long replaceDaily(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO daily_stat (appid, stat_date, review_count, negative_count,
                                        new_review_count, new_positive_count,
                                        edited_review_count, edited_positive_count, aggregated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        return replaceTable("daily_stat", rows, url, user, password, sql, (ps, r) -> {
            ps.setLong(1, r.<Long>getAs("appid"));
            ps.setDate(2, r.<Date>getAs("stat_date"));
            ps.setInt(3, toInt(r.getAs("review_count")));
            ps.setInt(4, toInt(r.getAs("negative_count")));
            ps.setInt(5, toInt(r.getAs("new_review_count")));
            ps.setInt(6, toInt(r.getAs("new_positive_count")));
            ps.setInt(7, toInt(r.getAs("edited_review_count")));
            ps.setInt(8, toInt(r.getAs("edited_positive_count")));
            ps.setTimestamp(9, r.<Timestamp>getAs("aggregated_at"));
        });
    }

    // ── language_stat ────────────────────────────────────────────

    private static void loadLanguage(Dataset<Row> reviews, Dataset<Row> games, Set<String> languages,
                                     Instant now, boolean dryRun,
                                     String url, String user, String password) {
        System.out.println();
        System.out.println("== language_stat ==");
        if (languages.isEmpty()) {
            // 표가 비어 있으면 한 줄도 못 넣는다. 조용히 0건 넣고 끝나는 것보다 멈추는 것이 낫다.
            throw new IllegalStateException(
                    "language 표가 비어 있다. FK 때문에 language_stat 을 넣을 수 없다 — V10 시드가 적용됐는지 볼 것.");
        }
        Dataset<Row> stat;
        try {
            stat = LanguageStatAggregator.aggregate(reviews, now).persist(StorageLevel.MEMORY_AND_DISK());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("language_stat 집계 거부: " + e.getMessage(), e);
        }
        try {
            long rows = stat.count();
            System.out.println("집계 행   " + rows + "건");

            // 표에 없는 언어 코드는 코드별로 몇 건인지 찍고 뺀다.
            Dataset<Row> unknown = stat.filter(col("language_code").isin(languages.toArray()).equalTo(false));
            List<Row> unknownCodes = unknown.groupBy("language_code")
                    .agg(org.apache.spark.sql.functions.count("*").alias("rows"),
                         org.apache.spark.sql.functions.sum("review_count").alias("reviews"))
                    .orderBy(col("reviews").desc()).collectAsList();
            if (!unknownCodes.isEmpty()) {
                System.out.println("⚠ language 표에 없는 코드 — 뺀다. 시드(V10)에 넣을 것:");
                for (Row r : unknownCodes) {
                    System.out.printf("    %-16s 행 %d · 리뷰 %d%n",
                            r.getAs("language_code"), r.<Long>getAs("rows"), r.<Long>getAs("reviews"));
                }
            }
            Dataset<Row> knownLang = stat.filter(col("language_code").isin(languages.toArray()));
            Dataset<Row> kept = inGame(knownLang, games);
            long keptRows = kept.count();
            long droppedGame = knownLang.count() - keptRows;
            if (droppedGame > 0) {
                System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + droppedGame + "건");
            }
            System.out.println("넣을 것   " + keptRows + "건");
            kept.orderBy(col("appid"), col("language_code")).show(5, false);
            if (dryRun) {
                System.out.println("--dry-run 이라 쓰지 않는다.");
                return;
            }
            long written = replaceLanguage(kept, url, user, password);
            long inDb = count(url, user, password, "language_stat");
            System.out.println("넣었다    " + written + "건 · DB language_stat " + inDb + "행");
            if (written != inDb) {
                throw new IllegalStateException("넣은 수와 DB 행 수가 다르다: " + written + " vs " + inDb);
            }
        } finally {
            stat.unpersist();
        }
    }

    /** language_stat 을 비우고 다시 넣는다 — 한 트랜잭션. */
    private static long replaceLanguage(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO language_stat (appid, language_code, review_count, positive_count, aggregated_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        return replaceTable("language_stat", rows, url, user, password, sql, (ps, r) -> {
            ps.setLong(1, r.<Long>getAs("appid"));
            ps.setString(2, r.getAs("language_code"));
            ps.setInt(3, toInt(r.getAs("review_count")));
            ps.setInt(4, toInt(r.getAs("positive_count")));
            ps.setTimestamp(5, r.<Timestamp>getAs("aggregated_at"));
        });
    }

    // ── 공통 ─────────────────────────────────────────────────────

    /** 행 하나를 PreparedStatement 에 채운다. */
    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, Row row) throws SQLException;
    }

    /**
     * 표를 비우고 {@code rows} 를 전부 넣는다. 드라이버의 연결 하나, 트랜잭션 하나.
     *
     * <p>{@code toLocalIterator()} 는 파티션을 하나씩 드라이버로 가져오므로 결과 전체를
     * 한 번에 메모리에 올리지 않는다. 넣는 도중 무엇이든 실패하면 롤백되어
     * TRUNCATE 까지 되돌아간다 — 표는 시작 전 상태 그대로다.
     */
    private static long replaceTable(String table, Dataset<Row> rows, String url, String user, String password,
                                     String insertSql, RowBinder binder) {
        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    // 표 이름은 이 파일 안의 상수다. 바깥 입력이 아니다.
                    st.executeUpdate("TRUNCATE TABLE " + table);
                }
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    int inBatch = 0;
                    java.util.Iterator<Row> it = rows.toLocalIterator();
                    while (it.hasNext()) {
                        binder.bind(ps, it.next());
                        ps.addBatch();
                        if (++inBatch >= BATCH) {
                            n += ps.executeBatch().length;
                            inBatch = 0;
                            if (n % 200_000 == 0) {
                                System.out.println("  … " + n + "건");
                            }
                        }
                    }
                    if (inBatch > 0) {
                        n += ps.executeBatch().length;
                    }
                }
                conn.commit();
                System.out.println("비우고 넣었다  " + table + " (한 트랜잭션)");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(table + " 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    /** game 에 있는 appid 행만 남긴다. left_semi 라 칼럼이 늘지 않는다. */
    private static Dataset<Row> inGame(Dataset<Row> stat, Dataset<Row> games) {
        return stat.join(games, stat.col("appid").equalTo(games.col("game_appid")), "left_semi");
    }

    /** 집계가 준 Long/Integer 를 INTEGER 칼럼에 맞춘다. null 은 0 — 세는 값이라 없는 것은 0 이다. */
    private static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        long l = ((Number) v).longValue();
        if (l > Integer.MAX_VALUE) {
            throw new IllegalStateException("INTEGER 범위를 넘는다: " + l);
        }
        return (int) l;
    }

    /** 있는 경로만 돌려준다. base 는 compaction 전에는 없다. */
    private static List<String> existing(SparkSession spark, String[] candidates) {
        List<String> out = new ArrayList<>();
        try {
            FileSystem fs = FileSystem.get(spark.sparkContext().hadoopConfiguration());
            for (String c : candidates) {
                if (fs.exists(new Path(c))) {
                    out.add(c);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HDFS 를 못 읽는다: " + e.getMessage(), e);
        }
        return out;
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

    private static Set<String> readStrings(String url, String user, String password, String sql) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return java.util.stream.Stream.iterate(rs, r -> {
                        try {
                            return r.next();
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }, r -> r)
                    .map(r -> {
                        try {
                            return r.getString(1);
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.toSet());
        } catch (SQLException e) {
            throw new IllegalStateException("조회 실패 (" + sql + "): " + e.getMessage(), e);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("환경변수 " + name + " 이 없다");
        }
        return v;
    }
}
