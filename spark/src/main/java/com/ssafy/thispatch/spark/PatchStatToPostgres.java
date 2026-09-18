package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.NewsLake;
import com.ssafy.thispatch.common.SparkSessions;
import com.ssafy.thispatch.common.TimeRule;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/**
 * 패치 전후 7일 리뷰 반응을 서비스 DB 에 적재한다 — {@code patch_stat}.
 *
 * <pre>
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.PatchStatToPostgres ... thispatch-spark.jar \
 *       --coverage-end 2026-09-17 [--coverage-start 2010-01-01] [--dry-run]
 * </pre>
 *
 * <p>계산은 {@link PatchStatAggregator} 가 한다 — 패치 판정(is_patch)이 참인 공지마다 게시일(KST)
 * 기준 앞 7일·뒤 7일의 리뷰 수와 긍정률. 여기서는 결과를 받아 넣는 것만 한다.
 *
 * <h2>--coverage-end 를 왜 손으로 주는가</h2>
 *
 * <p>집계기는 "리뷰 이력이 <b>완전한</b> 구간" 을 명시하라고 요구한다. 그 밖에 걸리는 패치 창은
 * 결과에서 빠진다 — 반쪽 창으로 반응을 지어내지 않기 위해서다. 완전한 마지막 날은 수집 상태를
 * 아는 사람이 정해야 한다: 전량 수집이 COMPLETED 로 끝난 날(2026-09-17)이 첫 값이고, 증분 수집이
 * 매일 돌면 파이프라인이 어제 날짜를 넣는다. 기본값을 두면 조용히 틀린 값이 들어가므로 필수다.
 *
 * <h2>어디에 있는 것만 넣는가</h2>
 *
 * <p>{@code patch_stat.gid} 는 {@code news} 를, {@code appid} 는 {@code game} 을 참조한다(FK).
 * 공지는 {@code news_raw} 에서 읽으므로 {@code news} 적재({@link NewsToPostgres})가 먼저 돌아야 한다.
 * 두 목록은 드라이버가 읽어 브로드캐스트 조인으로 거르고, 뺀 건수를 찍는다.
 *
 * <p>DB 는 드라이버만 만진다 — {@link LoaderSupport}.
 */
public final class PatchStatToPostgres {

    private static final int BATCH = 1_000;
    private static final double MAX_INVALID_PCT = 0.5;

    /** 스팀 리뷰 제도가 생긴 2010년 이전에는 리뷰가 없다. 전량 수집 뒤라 그때부터 이력이 완전하다. */
    private static final LocalDate DEFAULT_COVERAGE_START = LocalDate.of(2010, 1, 1);

    private PatchStatToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        LocalDate coverageStart = DEFAULT_COVERAGE_START;
        LocalDate coverageEnd = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case "--dry-run" -> dryRun = true;
                case "--coverage-start" -> coverageStart = LocalDate.parse(args[++i].trim());
                case "--coverage-end" -> coverageEnd = LocalDate.parse(args[++i].trim());
                default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
            }
        }
        if (coverageEnd == null) {
            throw new IllegalArgumentException("--coverage-end YYYY-MM-DD 가 필요하다 — 리뷰 이력이 완전한 마지막 날(그 날 00:00 KST 전까지)");
        }
        String url = LoaderSupport.env("DB_URL");
        String user = LoaderSupport.env("DB_USER");
        String password = LoaderSupport.env("DB_PASSWORD");
        Instant now = Instant.now();
        System.out.println("이력 구간  " + coverageStart + " 00:00 ~ " + coverageEnd + " 00:00 KST (창이 이 안에 다 들어가는 패치만)");

        SparkSession spark = SparkSessions.build("patch-stat-to-postgres");
        try {
            List<String> paths = LoaderSupport.existing(spark, HdfsPaths.reviewAll());
            if (paths.isEmpty() || LoaderSupport.existing(spark, new String[] {HdfsPaths.NEWS_RAW}).isEmpty()) {
                System.out.println("읽을 것이 없다 — review_raw 또는 news_raw 가 비어 있다.");
                return;
            }
            System.out.println("읽는 곳   " + String.join(", ", paths) + " · " + HdfsPaths.NEWS_RAW);

            // 공지: 판정 칼럼이 있어야 한다 (NewsLake.read 가 없으면 NEWS_SOURCE 로 읽어 is_patch 가 없다).
            Dataset<Row> news = NewsLake.read(spark);
            if (!java.util.Arrays.asList(news.columns()).contains("is_patch")) {
                throw new IllegalStateException("news_raw 에 is_patch 가 없다 — news-convert 가 판정을 붙였는지 볼 것.");
            }
            Dataset<Row> patches = news.select("gid", "appid", "published_ts", "is_patch", "collected_ts")
                    .persist(StorageLevel.MEMORY_AND_DISK());
            Dataset<Row> reviews = spark.read().parquet(paths.toArray(String[]::new))
                    .select("appid", "recommendationid", "created_ts", "updated_ts", "collected_ts", "voted_up")
                    .persist(StorageLevel.DISK_ONLY());
            try {
                System.out.println("공지 행   " + patches.count() + "건 · 패치 판정 true "
                        + patches.filter(col("is_patch").equalTo(true)).count() + "건 (판본 포함)");
                long total = reviews.count();
                System.out.println("리뷰 행   " + total + "건");

                long invalidRows = reviews.filter(DailyStatAggregator.invalidInput()).count();
                if (invalidRows > 0) {
                    double pct = 100.0 * invalidRows / total;
                    System.out.printf("⚠ 집계기 검증에 걸리는 행  %d건 (%.6f%%) — 뺀다%n", invalidRows, pct);
                    if (pct > MAX_INVALID_PCT) {
                        throw new IllegalStateException(String.format(
                                "검증에 걸린 행이 %.4f%% 로 한도(%.2f%%)를 넘는다. 데이터를 먼저 볼 것.", pct, MAX_INVALID_PCT));
                    }
                    reviews = reviews.filter(DailyStatAggregator.invalidInput().equalTo(false));
                }

                Dataset<Row> stat;
                try {
                    stat = PatchStatAggregator.aggregate(reviews, patches, coverageStart, coverageEnd, now)
                            .persist(StorageLevel.MEMORY_AND_DISK());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("patch_stat 집계 거부: " + e.getMessage(), e);
                }
                try {
                    long rows = stat.count();
                    System.out.println("집계 행   " + rows + "건 (창이 이력 안에 다 들어간 패치)");

                    List<Long> gameIds = LoaderSupport.readLongs(url, user, password, "SELECT appid FROM game");
                    List<String> gids = LoaderSupport.readStrings(url, user, password, "SELECT gid FROM news");
                    System.out.println("game      " + gameIds.size() + "개 · news " + gids.size() + "건 (드라이버가 읽음)");
                    if (gids.isEmpty()) {
                        throw new IllegalStateException("news 표가 비어 있다. FK 때문에 patch_stat 을 넣을 수 없다 — NewsToPostgres 를 먼저 돌릴 것.");
                    }
                    Dataset<Row> games = spark.createDataset(gameIds, Encoders.LONG()).toDF("game_appid");
                    Dataset<Row> newsGids = spark.createDataset(gids, Encoders.STRING()).toDF("news_gid");
                    Dataset<Row> inGame = stat.join(games, stat.col("appid").equalTo(games.col("game_appid")), "left_semi");
                    Dataset<Row> kept = inGame.join(newsGids, inGame.col("gid").equalTo(newsGids.col("news_gid")), "left_semi")
                            .persist(StorageLevel.MEMORY_AND_DISK());
                    try {
                        long inGameRows = inGame.count();
                        long keptRows = kept.count();
                        if (rows != inGameRows) {
                            System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + (rows - inGameRows) + "건");
                        }
                        if (inGameRows != keptRows) {
                            System.out.println("⚠ news 에 없는 gid 라 빼는 것  " + (inGameRows - keptRows) + "건 — news 적재가 news_raw 보다 오래됐다");
                        }
                        System.out.println("넣을 것   " + keptRows + "건");
                        kept.orderBy(col("after_review_count").desc()).show(5, false);
                        if (dryRun) {
                            System.out.println("--dry-run 이라 쓰지 않는다.");
                            return;
                        }
                        long written = replacePatchStat(kept, url, user, password);
                        long inDb = LoaderSupport.count(url, user, password, "patch_stat");
                        System.out.println("넣었다    " + written + "건 · DB patch_stat " + inDb + "행");
                        if (written != inDb) {
                            throw new IllegalStateException("넣은 수와 DB 행 수가 다르다: " + written + " vs " + inDb);
                        }
                    } finally {
                        kept.unpersist();
                    }
                } finally {
                    stat.unpersist();
                }
            } finally {
                reviews.unpersist();
                patches.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    /** patch_stat 을 비우고 다시 넣는다 — 참조하는 표가 없다. */
    private static long replacePatchStat(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO patch_stat (gid, appid, patched_at, before_review_count, before_positive_pct,
                                        after_review_count, after_positive_pct, delta_pct, stat_date, aggregated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        return LoaderSupport.replaceTable(new String[] {"patch_stat"}, rows, url, user, password, sql, BATCH,
                (ps, r) -> {
                    ps.setString(1, r.getAs("gid"));
                    ps.setLong(2, r.<Long>getAs("appid"));
                    ps.setTimestamp(3, r.<Timestamp>getAs("patched_at"));
                    ps.setInt(4, LoaderSupport.toInt(r.getAs("before_review_count")));
                    setDecimal(ps, 5, r.getAs("before_positive_pct"));
                    ps.setInt(6, LoaderSupport.toInt(r.getAs("after_review_count")));
                    setDecimal(ps, 7, r.getAs("after_positive_pct"));
                    setDecimal(ps, 8, r.getAs("delta_pct"));
                    ps.setDate(9, r.<Date>getAs("stat_date"));
                    ps.setTimestamp(10, r.<Timestamp>getAs("aggregated_at"));
                });
    }

    /** 리뷰가 0건인 쪽의 긍정률은 null 이다 — 0% 로 지어내지 않는다. */
    private static void setDecimal(java.sql.PreparedStatement ps, int idx, Object v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, Types.NUMERIC);
        } else {
            ps.setBigDecimal(idx, (BigDecimal) v);
        }
    }

    /** 오늘(KST) 의 하루 전 — 파이프라인이 "어제까지 완전" 으로 부를 때 쓰는 값. 손 실행에서는 명시한다. */
    static LocalDate yesterdayKst() {
        return LocalDate.now(TimeRule.ZONE).minusDays(1);
    }
}
