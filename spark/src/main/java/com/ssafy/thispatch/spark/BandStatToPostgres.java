package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/**
 * 플레이타임 구간 통계를 서비스 DB 에 적재한다 — {@code band_stat}.
 *
 * <pre>
 *   DB_URL=jdbc:postgresql://127.0.0.1:15432/thispatch DB_USER=thispatch DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.BandStatToPostgres ... thispatch-spark.jar [--dry-run]
 * </pre>
 *
 * <p>계산은 {@link BandStatAggregator} 가 한다 — 게임마다 최신본 리뷰의 플레이타임 분포를
 * 사분위로 잘라 B1~B4 네 구간. 여기서는 그 결과를 받아 넣는 것만 한다. 규칙을 바꾸지 않는다.
 *
 * <h2>왜 전체 기간인가</h2>
 *
 * <p>백엔드는 구간 경계(playtime_from · playtime_to)를 전체 기간 리뷰로 계산된 값으로 쓰고,
 * 최근 14일 리뷰를 그 경계에 맞춰 나눈다 (statistics.md "최근 14일로 경계를 새로 만들지 않는다").
 * 그래서 입력은 {@code review_raw} 전량이고, 리뷰 하나에 최신본 하나다.
 *
 * <h2>왜 band_topic_stat 이 비어 있어야 하는가</h2>
 *
 * <p>{@code band_topic_stat.band_stat_id} 가 이 표의 BIGSERIAL PK 를 참조한다. 표를 비우고 다시
 * 넣으면 id 가 새로 매겨져 토픽 통계가 엉뚱한 구간을 가리킨다. 그래서 그 표에 행이 있으면
 * 멈춘다. 토픽 통계(S15P21A202-251 후반 · -252)는 이 표를 넣은 뒤 id 를 읽어 넣는다.
 *
 * <p>DB 는 드라이버만 만진다 — {@link LoaderSupport}. 실행 옵션은 language_stat 과 같은 실측값
 * (1.7억 행 윈도우: executor 6g·2코어×8 · shuffle 240 · AQE 병합 끔 · 필요 칼럼만 DISK_ONLY).
 */
public final class BandStatToPostgres {

    private static final int BATCH = 1_000;

    /** 집계기 검증에 걸린 행을 빼고 진행할 수 있는 최대 비율(%). ReviewStatsToPostgres 와 같다. */
    private static final double MAX_INVALID_PCT = 0.5;

    private BandStatToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        for (String a : args) {
            switch (a.trim()) {
                case "--dry-run" -> dryRun = true;
                default -> throw new IllegalArgumentException("모르는 인자: " + a);
            }
        }
        String url = LoaderSupport.env("DB_URL");
        String user = LoaderSupport.env("DB_USER");
        String password = LoaderSupport.env("DB_PASSWORD");
        Instant now = Instant.now();

        SparkSession spark = SparkSessions.build("band-stat-to-postgres");
        try {
            List<String> paths = LoaderSupport.existing(spark, HdfsPaths.reviewAll());
            if (paths.isEmpty()) {
                System.out.println("읽을 것이 없다: " + String.join(", ", HdfsPaths.reviewAll()));
                return;
            }
            System.out.println("읽는 곳   " + String.join(", ", paths));

            // 집계기가 보는 7칼럼만, 디스크에만 붙잡는다 (본문 포함 전체 칼럼을 힙에 올렸다가 OOM 난 적 있다).
            Dataset<Row> reviews = spark.read().parquet(paths.toArray(String[]::new))
                    .select("appid", "recommendationid", "created_ts", "updated_ts", "collected_ts",
                            "voted_up", "playtime_at_review")
                    .persist(StorageLevel.DISK_ONLY());
            try {
                long total = reviews.count();
                System.out.println("리뷰 행   " + total + "건 (판본 · 중복 수집 포함)");

                // selectLatestReviews 는 나쁜 행이 하나라도 있으면 전체를 거부한다. 미리 갈라내 세고 뺀다.
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

                Dataset<Row> latest;
                try {
                    latest = BandStatAggregator.selectLatestReviews(reviews).persist(StorageLevel.DISK_ONLY());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("band_stat 입력 거부: " + e.getMessage(), e);
                }
                try {
                    long latestRows = latest.count();
                    System.out.println("최신본    " + latestRows + "건 (리뷰마다 하나)");

                    // 플레이타임 결측·음수는 구간 계산에서 빠진다. 몇 건인지 찍는다 — 조용히 빠지지 않게.
                    Row q = BandStatAggregator.qualityCounts(latest)
                            .agg(org.apache.spark.sql.functions.sum("missing_playtime_count").alias("missing"),
                                 org.apache.spark.sql.functions.sum("negative_playtime_count").alias("negative"),
                                 org.apache.spark.sql.functions.sum("included_review_count").alias("included"))
                            .first();
                    System.out.println("플레이타임  포함 " + q.getAs("included") + "건 · 결측 " + q.getAs("missing")
                            + "건 · 음수 " + q.getAs("negative") + "건");

                    Dataset<Row> stat = BandStatAggregator.aggregateLatestReviews(latest, now)
                            .persist(StorageLevel.MEMORY_AND_DISK());
                    try {
                        long rows = stat.count();
                        List<Long> gameIds = LoaderSupport.readLongs(url, user, password, "SELECT appid FROM game");
                        Dataset<Row> games = spark.createDataset(gameIds, Encoders.LONG()).toDF("game_appid");
                        System.out.println("game      " + gameIds.size() + "개 (드라이버가 읽음)");
                        Dataset<Row> kept = stat.join(games, stat.col("appid").equalTo(games.col("game_appid")), "left_semi")
                                .persist(StorageLevel.MEMORY_AND_DISK());
                        try {
                            long keptRows = kept.count();
                            System.out.println("집계 행   " + rows + "건 (게임당 4구간)");
                            if (rows != keptRows) {
                                System.out.println("⚠ game 에 없는 appid 라 빼는 것  " + (rows - keptRows) + "건");
                            }
                            System.out.println("넣을 것   " + keptRows + "건");
                            kept.filter(col("appid").equalTo(730)).orderBy("band_no").show(4, false);
                            if (dryRun) {
                                System.out.println("--dry-run 이라 쓰지 않는다.");
                                return;
                            }
                            long topics = LoaderSupport.count(url, user, password, "band_topic_stat");
                            if (topics > 0) {
                                throw new IllegalStateException("band_topic_stat 에 " + topics + "행이 있다. band_stat 을 비우면 "
                                        + "band_stat_id 가 새로 매겨져 토픽 통계가 어긋난다 — 그 표를 먼저 비우고 다시 넣는 순서로 돌릴 것.");
                            }
                            long written = replaceBandStat(kept, url, user, password);
                            long inDb = LoaderSupport.count(url, user, password, "band_stat");
                            System.out.println("넣었다    " + written + "건 · DB band_stat " + inDb + "행");
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
                    latest.unpersist();
                }
            } finally {
                reviews.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    /** band_stat 을 비우고 다시 넣는다 — band_topic_stat 이 FK 로 참조하므로 함께 TRUNCATE (호출 전 0행 확인). */
    private static long replaceBandStat(Dataset<Row> rows, String url, String user, String password) {
        String sql = """
                INSERT INTO band_stat (appid, band_no, playtime_from, playtime_to, review_count, positive_count, aggregated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        return LoaderSupport.replaceTable(new String[] {"band_stat", "band_topic_stat"}, rows, url, user, password, sql, BATCH,
                (ps, r) -> {
                    ps.setLong(1, r.<Long>getAs("appid"));
                    ps.setShort(2, r.<Short>getAs("band_no"));
                    ps.setInt(3, LoaderSupport.toInt(r.getAs("playtime_from")));
                    Object to = r.getAs("playtime_to");
                    if (to == null) {
                        ps.setNull(4, Types.INTEGER);
                    } else {
                        ps.setInt(4, LoaderSupport.toInt(to));
                    }
                    ps.setInt(5, LoaderSupport.toInt(r.getAs("review_count")));
                    ps.setInt(6, LoaderSupport.toInt(r.getAs("positive_count")));
                    ps.setTimestamp(7, r.<Timestamp>getAs("aggregated_at"));
                });
    }
}
