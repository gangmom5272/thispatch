package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ssafy.thispatch.common.SparkSessions;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DB 없이 검사할 수 있는 부분 — 검증 조건과 최신본 고르기. */
class RecentReviewToPostgresTest {
    private static SparkSession spark;
    private static final StructType SCHEMA = new StructType()
            .add("recommendationid", DataTypes.LongType).add("appid", DataTypes.LongType)
            .add("review_text", DataTypes.StringType).add("voted_up", DataTypes.BooleanType)
            .add("votes_up", DataTypes.IntegerType).add("playtime_at_review", DataTypes.IntegerType)
            .add("language_code", DataTypes.StringType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("recent_review_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row row(Long id, Long app, String text, Boolean vote, Integer votes, Integer playtime,
                           String lang, Long created, Long updated, Long collected) {
        return RowFactory.create(id, app, text, vote, votes, playtime, lang, created, updated, collected);
    }
    private static Row ok(long id, long updated, long collected) {
        return row(id, 730L, "좋다", true, 3, 120, "koreana", 1_000L, updated, collected);
    }
    private static Dataset<Row> input(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), SCHEMA); }

    @Test void keepsOnlyTheLatestVersionOfEachReview() {
        List<Row> out = RecentReviewToPostgres.latestPerReview(input(
                        row(11L, 730L, "처음", true, 1, 10, "koreana", 100L, 100L, 101L),
                        row(11L, 730L, "고침", false, 2, 20, "koreana", 100L, 200L, 201L),
                        row(12L, 730L, "다른 리뷰", true, 0, null, "english", 150L, 150L, 151L)))
                .orderBy("recommendationid").collectAsList();
        assertEquals(2, out.size());
        assertEquals("고침", out.get(0).getAs("review_text"));
        assertEquals(Boolean.FALSE, out.get(0).getAs("voted_up"));
        assertEquals(200L, (long) out.get(0).getAs("updated_ts"));
        assertNull(out.get(1).getAs("playtime_at_review"));
        assertFalse(Arrays.asList(out.get(0).schema().fieldNames()).contains("collected_ts"),
                "collected_ts 는 표에 없으니 떼어낸다");
    }

    @Test void sameVersionCollectedTwiceKeepsOneRow() {
        List<Row> out = RecentReviewToPostgres.latestPerReview(input(
                ok(21, 500L, 501L), ok(21, 500L, 900L))).collectAsList();
        assertEquals(1, out.size());
    }

    /** band_stat 구간(from ≤ playtime < to, 마지막은 to=null)으로 band_no 를 붙인다. 없는 게임·플레이타임 없음은 null 로 남긴다. */
    @Test void assignsBandFromBandStatRanges() {
        Dataset<Row> reviews = input(
                row(1L, 730L, "a", true, 0, 5, "english", 100L, 100L, 100L),      // 0~10  → 1
                row(2L, 730L, "b", true, 0, 10, "english", 100L, 100L, 100L),     // 10~50 → 2 (경계는 아래 구간 포함)
                row(3L, 730L, "c", true, 0, 999, "english", 100L, 100L, 100L),    // 200~  → 4
                row(4L, 730L, "d", true, 0, null, "english", 100L, 100L, 100L),   // 플레이타임 없음 → null
                row(5L, 570L, "e", true, 0, 5, "english", 100L, 100L, 100L));     // band_stat 에 없는 게임 → null
        Dataset<Row> bands = spark.createDataFrame(Arrays.asList(
                RowFactory.create(730L, (short) 1, 0, 10), RowFactory.create(730L, (short) 2, 10, 50),
                RowFactory.create(730L, (short) 3, 50, 200), RowFactory.create(730L, (short) 4, 200, null)),
                RecentReviewToPostgres.BAND);
        java.util.Map<Long, Short> got = new java.util.HashMap<>();
        for (Row r : RecentReviewToPostgres.assignBand(reviews, bands).collectAsList()) {
            got.put(r.getAs("recommendationid"), r.getAs("band_no"));
        }
        assertEquals(5, got.size());
        assertEquals((short) 1, (short) got.get(1L));
        assertEquals((short) 2, (short) got.get(2L));
        assertEquals((short) 4, (short) got.get(3L));
        assertNull(got.get(4L));
        assertNull(got.get(5L));
    }

    /** 증분 모드는 오늘·어제 delta 파티션만 읽는다. 자정을 넘긴 수집이 어제 파티션에 남기 때문이다. */
    @Test void incrementalReadsTodayAndYesterdayDeltaOnly() {
        String[] paths = RecentReviewToPostgres.deltaPathsFor(java.time.LocalDate.of(2026, 9, 22), 2);
        assertEquals(2, paths.length);
        assertTrue(paths[0].endsWith("/review_raw/delta/dt=2026-09-22"), paths[0]);
        assertTrue(paths[1].endsWith("/review_raw/delta/dt=2026-09-21"), paths[1]);
    }

    /** 증분은 UNIQUE 없이 간다 — UPDATE 는 recommendationid 로 찾되 더 새 판본일 때만, INSERT 는 ON CONFLICT 없이. */
    @Test void incrementalUpdatesOnlyNewerVersionAndInsertsPlainly() {
        String upd = RecentReviewToPostgres.updateSql();
        assertTrue(upd.contains("WHERE recommendationid = ? AND updated_ts <= ?"), upd);
        assertTrue(upd.contains("band_no = ?"));
        String ins = RecentReviewToPostgres.insertSql();
        assertFalse(ins.contains("ON CONFLICT"), ins);
        assertTrue(ins.contains("INSERT INTO recent_review"));
    }

    @Test void invalidInputCatchesWhatInsertWouldReject() {
        Dataset<Row> rows = input(
                ok(1, 10L, 11L),                                                         // 정상
                row(null, 730L, "x", true, 0, null, "koreana", 1L, 2L, 3L),              // id null
                row(2L, 0L, "x", true, 0, null, "koreana", 1L, 2L, 3L),                  // appid 0
                row(3L, 730L, null, true, 0, null, "koreana", 1L, 2L, 3L),               // 본문 null
                row(4L, 730L, "x", null, 0, null, "koreana", 1L, 2L, 3L),                // voted_up null
                row(5L, 730L, "x", true, 0, null, " koreana", 1L, 2L, 3L),               // 앞 공백
                row(6L, 730L, "x", true, 0, null, "", 1L, 2L, 3L),                       // 빈 코드
                row(7L, 730L, "x", true, 0, null, "koreana", null, 2L, 3L),              // created null
                row(8L, 730L, "x", true, null, null, "koreana", 1L, 2L, 3L));            // votes null 은 허용(0 으로)
        List<Row> bad = rows.filter(RecentReviewToPostgres.invalidInput()).collectAsList();
        assertEquals(7, bad.size());
        List<Row> good = rows.filter(RecentReviewToPostgres.invalidInput().equalTo(false))
                .orderBy("recommendationid").collectAsList();
        assertEquals(2, good.size());
        assertEquals(1L, (long) good.get(0).getAs("recommendationid"));
        assertEquals(8L, (long) good.get(1).getAs("recommendationid"));
        assertTrue(good.get(1).isNullAt(good.get(1).fieldIndex("votes_up")));
    }
}
