package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.length;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.trim;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;

/**
 * 최근 리뷰 원문을 서비스 DB 에 적재한다 — {@code recent_review}.
 *
 * <pre>
 *   DB_URL=jdbc:postgresql://127.0.0.1:15432/thispatch \
 *   DB_USER=thispatch DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.RecentReviewToPostgres ... thispatch-spark.jar
 *
 *   ... thispatch-spark.jar --dry-run          세어만 보고 쓰지 않는다
 *   ... thispatch-spark.jar --days 14          며칠치를 남길지 (기본 14)
 *   ... thispatch-spark.jar --path hdfs://.../review_raw/delta/dt=2026-09-17
 *                                              이 경로만 읽는다 (시험용 · --dry-run 과 함께만)
 * </pre>
 *
 * <h2>왜 전량이 아니라 최근 것만 넣는가</h2>
 *
 * <p>백엔드는 이 표에서 "KST 기준 오늘을 포함한 최근 14일의 최신 리뷰" 만 조회한다
 * (backend/docs/api/review.md · {@code ReviewPeriod.recentFourteenDays}). 리뷰 목록 ·
 * 대표 리뷰 · 플레이타임 구간 분석 · 언어 통계 화면이 전부 그 창 안에서만 읽는다.
 * 1.7억 건 전체를 본문째 Postgres 에 넣을 이유가 없다 — 14일치는 90만 건 안팎이다
 * (2026-09-18 daily_stat 실측 889,027건 · 게임 28,092개).
 *
 * <p>창보다 하루 여유를 둔다. 자정 전후에 돌아도 백엔드가 보는 14일이 통째로 들어 있게.
 * 백엔드가 어차피 기간으로 다시 거르므로 여유분은 화면에 보이지 않는다.
 *
 * <h2>왜 리뷰마다 최신본 하나만 넣는가</h2>
 *
 * <p>{@code review_raw} 는 (recommendationid, updated_ts) 단위라 수정된 리뷰는 판본이
 * 여럿이다. 백엔드는 조회 때 {@code DISTINCT ON (recommendationid) … ORDER BY updated_ts DESC}
 * 로 최신본을 고른 뒤 기간을 거른다. 여기서 최신본만 넣어도 결과가 같고 표는 작아진다.
 * 같은 판본이 여러 번 수집됐으면(collected_ts 만 다름) 가장 늦게 수집된 것을 남긴다.
 *
 * <h2>왜 DB 는 드라이버만 만지는가</h2>
 *
 * <p>{@link ReviewStatsToPostgres} 와 같다 — 서비스 DB 는 마스터의 SSH 터널로만 닿고
 * executor 는 거기 못 간다. 참조 목록(game · language)은 드라이버가 읽어 브로드캐스트
 * 조인하고, 결과는 {@code toLocalIterator()} 로 드라이버가 받아 연결 하나 · 트랜잭션 하나로
 * 넣는다. 90만 건 × 본문이라도 파티션 단위로 흘러오므로 드라이버 메모리에 다 올리지 않는다.
 *
 * <h2>왜 review_topic 이 비어 있어야 하는가</h2>
 *
 * <p>{@code review_topic.review_id} 가 이 표의 BIGSERIAL PK 를 참조한다. 표를 비우고 다시
 * 넣으면 review_id 가 전부 새로 매겨지므로 이미 들어 있는 토픽 행은 엉뚱한 리뷰를 가리키게
 * 된다. 그래서 토픽 행이 있으면 <b>멈춘다</b> — 조용히 지우거나 어긋나게 두지 않는다.
 * 토픽 적재(AI 쪽 Loader)는 이 표를 넣은 뒤에 recommendationid → review_id 로 바꿔 넣는다.
 * 매일 창을 미는 증분 방식은 그 순서를 함께 정해야 해서 파이프라인 연결(-26)에서 다룬다.
 *
 * <h2>무엇을 빼고 얼마나 뺐는지 찍는다</h2>
 *
 * <p>NOT NULL 칼럼이 비었거나 FK(game · language)에 없는 행은 INSERT 가 통째로 실패하므로
 * 미리 갈라내 <b>이유별 건수를 찍고</b> 뺀다. 조용히 버리지 않는다. 비율이 한도를 넘으면
 * 데이터가 잘못된 것이니 멈춘다.
 */
public final class RecentReviewToPostgres {

    /** 백엔드가 조회하는 창. backend/docs/api/review.md 의 "최근 14일" 과 같다. */
    static final int DEFAULT_WINDOW_DAYS = 14;

    /** 창에 더 붙이는 여유(일). 자정 전후 실행과 시계 차이를 덮는다. */
    static final int MARGIN_DAYS = 1;

    /** 백엔드가 날짜를 자르는 시간대 (backend {@code TimeRule.ZONE}). */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한 번에 DB 로 보내는 행 수. 본문이 있어 ReviewStatsToPostgres(1,000) 보다 작게. */
    private static final int BATCH = 500;

    /** 검증에 걸려 빼는 행의 최대 비율(%). 넘으면 멈춘다. */
    private static final double MAX_INVALID_PCT = 0.5;

    private RecentReviewToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        int days = DEFAULT_WINDOW_DAYS;
        String path = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case "--dry-run" -> dryRun = true;
                case "--days" -> days = Integer.parseInt(i + 1 < args.length ? args[++i].trim() : "");
                case "--path" -> path = (i + 1 < args.length) ? args[++i].trim() : null;
                default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
            }
        }
        if (path != null && !dryRun) {
            throw new IllegalArgumentException("--path 는 --dry-run 과 함께만 쓴다 (일부만 읽어 표를 덮어쓰지 않게)");
        }
        if (days < 1 || days > 366) {
            throw new IllegalArgumentException("--days 는 1~366");
        }

        String url = env("DB_URL");
        String user = env("DB_USER");
        String password = env("DB_PASSWORD");

        // 오늘(KST) 을 포함한 days 일 + 여유. 백엔드의 today.minusDays(13) 과 같은 셈법이다.
        LocalDate today = LocalDate.now(KST);
        LocalDate from = today.minusDays(days - 1L + MARGIN_DAYS);
        long cutoffEpoch = from.atStartOfDay(KST).toInstant().getEpochSecond();
        System.out.println("창        " + from + " 00:00 KST 이후 수정된 리뷰 (오늘 " + today + " 포함 "
                + days + "일 + 여유 " + MARGIN_DAYS + "일) · updated_ts >= " + cutoffEpoch);

        SparkSession spark = SparkSessions.build("recent-review-to-postgres");
        try {
            List<String> paths = path != null
                    ? existing(spark, new String[] {path})
                    : existing(spark, HdfsPaths.reviewAll());
            if (paths.isEmpty()) {
                System.out.println("읽을 것이 없다: " + String.join(", ", HdfsPaths.reviewAll()));
                return;
            }
            System.out.println("읽는 곳   " + String.join(", ", paths));

            // 필요한 칼럼만, 창 안의 것만. updated_ts 조건은 파케이 통계로 파일·로우그룹 단위로
            // 걸러지므로 22GB 를 다 풀어 읽지는 않는다. 본문이 있어 DISK_ONLY 로만 붙잡는다.
            Dataset<Row> window = spark.read().parquet(paths.toArray(String[]::new))
                    .select("recommendationid", "appid", "review_text", "voted_up", "votes_up",
                            "playtime_at_review", "language_code", "created_ts", "updated_ts", "collected_ts")
                    .filter(col("updated_ts").geq(cutoffEpoch))
                    .persist(StorageLevel.DISK_ONLY());
            try {
                long total = window.count();
                System.out.println("창 안 행  " + total + "건 (판본 · 중복 수집 포함)");
                if (total == 0) {
                    throw new IllegalStateException("창 안에 리뷰가 한 건도 없다. 변환(review_raw)이 최신인지 볼 것.");
                }

                // NOT NULL 칼럼과 키 범위. 걸리면 INSERT 가 통째로 실패하므로 미리 갈라낸다.
                Dataset<Row> invalid = window.filter(invalidInput());
                long invalidRows = invalid.count();
                if (invalidRows > 0) {
                    double pct = 100.0 * invalidRows / total;
                    System.out.printf("⚠ 넣을 수 없는 행  %d건 (%.6f%%) — 뺀다. 이유별:%n", invalidRows, pct);
                    for (String[] why : new String[][] {
                            {"recommendationid null·<=0", "recommendationid IS NULL OR recommendationid <= 0"},
                            {"appid null·<=0", "appid IS NULL OR appid <= 0"},
                            {"review_text null", "review_text IS NULL"},
                            {"voted_up null", "voted_up IS NULL"},
                            {"created_ts·updated_ts null", "created_ts IS NULL OR updated_ts IS NULL"},
                            {"language_code 비었거나 잘못됨", "language_code IS NULL OR length(trim(language_code)) = 0 "
                                    + "OR length(language_code) > 20 OR language_code <> trim(language_code)"}}) {
                        long c = invalid.filter(why[1]).count();
                        if (c > 0) {
                            System.out.printf("    %-28s %d건%n", why[0], c);
                        }
                    }
                    if (pct > MAX_INVALID_PCT) {
                        throw new IllegalStateException(String.format(
                                "넣을 수 없는 행이 %.4f%% 로 한도(%.2f%%)를 넘는다. 데이터를 먼저 볼 것.", pct, MAX_INVALID_PCT));
                    }
                }
                Dataset<Row> latest = latestPerReview(window.filter(invalidInput().equalTo(false)))
                        .persist(StorageLevel.DISK_ONLY());
                try {
                    long latestRows = latest.count();
                    System.out.println("최신본    " + latestRows + "건 (리뷰마다 하나)");

                    // 참조 표는 드라이버가 읽어 작은 DataFrame 으로 (클래스 주석 참고)
                    List<Long> gameIds = new ArrayList<>(readLongs(url, user, password, "SELECT appid FROM game"));
                    Dataset<Row> games = spark.createDataset(gameIds, Encoders.LONG()).toDF("game_appid");
                    Set<String> languages = readStrings(url, user, password, "SELECT language_code FROM language");
                    System.out.println("game      " + gameIds.size() + "개 · language " + languages.size() + "개 코드 (드라이버가 읽음)");
                    if (languages.isEmpty()) {
                        throw new IllegalStateException("language 표가 비어 있다. FK 때문에 한 줄도 못 넣는다 — V10 시드를 볼 것.");
                    }

                    Dataset<Row> unknownLang = latest.filter(col("language_code").isin(languages.toArray()).equalTo(false));
                    List<Row> unknownCodes = unknownLang.groupBy("language_code").count()
                            .orderBy(col("count").desc()).collectAsList();
                    if (!unknownCodes.isEmpty()) {
                        System.out.println("⚠ language 표에 없는 코드 — 뺀다. 시드에 넣을 것:");
                        for (Row r : unknownCodes) {
                            System.out.printf("    %-16s %d건%n", r.getAs("language_code"), r.<Long>getAs("count"));
                        }
                    }
                    Dataset<Row> knownLang = latest.filter(col("language_code").isin(languages.toArray()));
                    // band_no — 백엔드의 「플레이타임 구간 × 토픽」 집계와 구간별 대표 리뷰가 recent_review.band_no 를 직접 읽는다
                    // (PlaytimeAnalysisRepository). 2026-09-21 실측: 96만 행 전부 NULL 이라 구간 화면이 비었다.
                    // 구간 경계는 band_stat(appid 별 4분위)이 정본이라 드라이버가 읽어 브로드캐스트 조인한다.
                    List<Row> bandRows = readBands(url, user, password);
                    Dataset<Row> bands = spark.createDataFrame(bandRows, BAND);
                    System.out.println("band_stat  " + bandRows.size() + "행 (드라이버가 읽음)" + (bandRows.isEmpty() ? " — 비어 있어 band_no 는 전부 NULL 이 된다. band_stat 을 먼저 적재할 것" : ""));
                    Dataset<Row> kept = assignBand(
                                knownLang.join(games, knownLang.col("appid").equalTo(games.col("game_appid")), "left_semi"), bands)
                            .persist(StorageLevel.DISK_ONLY());
                    try {
                        long keptRows = kept.count();
                        long droppedGame = knownLang.count() - keptRows;
                        if (droppedGame > 0) {
                            System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + droppedGame + "건");
                        }
                        long nullVotes = kept.filter(col("votes_up").isNull()).count();
                        if (nullVotes > 0) {
                            System.out.println("⚠ votes_up 이 null 인 행  " + nullVotes + "건 — 0 으로 넣는다 (NOT NULL · 세는 값)");
                        }
                        long noBand = kept.filter(col("band_no").isNull()).count();
                        System.out.println("넣을 것   " + keptRows + "건 · band_no 없음 " + noBand + "건 (플레이타임 없음 · band_stat 에 없는 게임)");
                        System.out.println("게임별 상위:");
                        kept.groupBy("appid").count().orderBy(col("count").desc()).show(5, false);
                        if (dryRun) {
                            System.out.println("--dry-run 이라 쓰지 않는다.");
                            return;
                        }

                        long topics = count(url, user, password, "review_topic");
                        if (topics > 0) {
                            throw new IllegalStateException("review_topic 에 " + topics + "행이 있다. recent_review 를 비우면 "
                                    + "review_id 가 새로 매겨져 토픽이 엉뚱한 리뷰를 가리킨다 — 토픽 적재 순서를 먼저 정할 것.");
                        }
                        long written = replaceRecentReview(kept, url, user, password);
                        long inDb = count(url, user, password, "recent_review");
                        System.out.println("넣었다    " + written + "건 · DB recent_review " + inDb + "행");
                        if (written != inDb) {
                            throw new IllegalStateException("넣은 수와 DB 행 수가 다르다: " + written + " vs " + inDb);
                        }
                    } finally {
                        kept.unpersist();
                    }
                } finally {
                    latest.unpersist();
                }
            } finally {
                window.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    // ── 순수 Spark 부분 (테스트가 여기를 본다) ──────────────────────

    /** band_stat 의 구간 경계. playtime_to 가 null 이면 마지막 구간(위로 열림). */
    static final StructType BAND = new StructType()
            .add("band_appid", DataTypes.LongType, false)
            .add("band_no", DataTypes.ShortType, false)
            .add("playtime_from", DataTypes.IntegerType, false)
            .add("playtime_to", DataTypes.IntegerType, true);

    /**
     * 리뷰의 playtime_at_review 를 그 게임의 band_stat 구간에 넣어 band_no 를 붙인다.
     * 규칙은 BandStatAggregator 와 같다: from ≤ playtime < to, 마지막 구간은 to 가 null.
     * 플레이타임이 없거나 게임이 band_stat 에 없으면 null — 행은 남긴다(left join).
     */
    static Dataset<Row> assignBand(Dataset<Row> reviews, Dataset<Row> bands) {
        Column on = reviews.col("appid").equalTo(bands.col("band_appid"))
                .and(reviews.col("playtime_at_review").isNotNull())
                .and(reviews.col("playtime_at_review").geq(bands.col("playtime_from")))
                .and(bands.col("playtime_to").isNull().or(reviews.col("playtime_at_review").lt(bands.col("playtime_to"))));
        return reviews.join(bands, on, "left_outer")
                .drop("band_appid", "playtime_from", "playtime_to");
    }

    private static List<Row> readBands(String url, String user, String password) {
        List<Row> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT appid, band_no, playtime_from, playtime_to FROM band_stat")) {
            while (rs.next()) {
                Integer to = rs.getObject(4, Integer.class);
                out.add(RowFactory.create(rs.getLong(1), rs.getShort(2), rs.getInt(3), to));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("band_stat 조회 실패: " + e.getMessage(), e);
        }
        return out;
    }

    /** INSERT 가 거부할 행. NOT NULL 칼럼 · 키 범위 · language_code 형식. */
    static Column invalidInput() {
        return col("recommendationid").isNull().or(col("recommendationid").leq(0))
                .or(col("appid").isNull()).or(col("appid").leq(0))
                .or(col("review_text").isNull())
                .or(col("voted_up").isNull())
                .or(col("created_ts").isNull()).or(col("updated_ts").isNull())
                .or(col("language_code").isNull())
                .or(length(trim(col("language_code"))).equalTo(0))
                .or(length(col("language_code")).gt(20))
                .or(col("language_code").notEqual(trim(col("language_code"))));
    }

    /**
     * 리뷰(recommendationid)마다 판본 하나만 남긴다 — updated_ts 가 가장 늦은 것,
     * 같으면 collected_ts 가 가장 늦은 것(같은 판본을 여러 번 수집한 경우).
     * collected_ts 는 여기까지만 쓰고 떼어낸다. 표에 그 칼럼이 없다.
     */
    static Dataset<Row> latestPerReview(Dataset<Row> rows) {
        Column rank = row_number().over(Window.partitionBy("recommendationid")
                .orderBy(col("updated_ts").desc(), col("collected_ts").desc_nulls_last()));
        return rows.withColumn("rn", rank).filter(col("rn").equalTo(1))
                .select("recommendationid", "appid", "review_text", "voted_up", "votes_up",
                        "playtime_at_review", "language_code", "created_ts", "updated_ts");
    }

    // ── DB ─────────────────────────────────────────────────────────

    /**
     * recent_review 를 비우고 전부 넣는다 — 드라이버의 연결 하나, 트랜잭션 하나.
     *
     * <p>review_topic 이 이 표를 FK 로 참조해서 TRUNCATE 는 두 표를 함께 지정해야 한다
     * (Postgres 는 참조되는 표 단독 TRUNCATE 를 거부한다). 호출 전에 review_topic 이
     * 비어 있음을 확인했으므로 여기서 지우는 토픽 행은 없다.
     * 실패하면 롤백되어 표는 시작 전 그대로다.
     */
    private static long replaceRecentReview(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO recent_review (recommendationid, appid, review_text, voted_up, votes_up,
                                           playtime_at_review, language_code, band_no, created_ts, updated_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("TRUNCATE TABLE review_topic, recent_review");
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int inBatch = 0;
                    java.util.Iterator<Row> it = rows.toLocalIterator();
                    while (it.hasNext()) {
                        Row r = it.next();
                        ps.setLong(1, r.<Long>getAs("recommendationid"));
                        ps.setLong(2, r.<Long>getAs("appid"));
                        ps.setString(3, r.getAs("review_text"));
                        ps.setBoolean(4, r.<Boolean>getAs("voted_up"));
                        Integer votes = r.getAs("votes_up");
                        ps.setInt(5, votes == null ? 0 : votes);
                        Integer playtime = r.getAs("playtime_at_review");
                        if (playtime == null) {
                            ps.setNull(6, Types.INTEGER);
                        } else {
                            ps.setInt(6, playtime);
                        }
                        ps.setString(7, r.getAs("language_code"));
                        Short band = r.getAs("band_no");
                        if (band == null) {
                            ps.setNull(8, Types.SMALLINT);
                        } else {
                            ps.setShort(8, band);
                        }
                        ps.setTimestamp(9, Timestamp.from(Instant.ofEpochSecond(r.<Long>getAs("created_ts"))));
                        ps.setTimestamp(10, Timestamp.from(Instant.ofEpochSecond(r.<Long>getAs("updated_ts"))));
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
                }
                conn.commit();
                System.out.println("비우고 넣었다  recent_review (한 트랜잭션)");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("recent_review 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    // ── 공통 ─────────────────────────────────────────────────────

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
            Set<String> out = new java.util.HashSet<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
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
