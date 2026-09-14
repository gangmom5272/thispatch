package com.ssafy.dispatch.spark;

import com.ssafy.dispatch.common.SparkSessions;
import com.ssafy.dispatch.common.TimeRule;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.apache.spark.sql.functions.*;

class PatchStatAggregatorTest {
    private static SparkSession spark;
    private static final LocalDate START = LocalDate.parse("2026-09-01");
    private static final LocalDate END = LocalDate.parse("2026-09-15");
    private static final long PATCH_DAY = TimeRule.startOfDay(LocalDate.parse("2026-09-08"));
    private static final long RUN_TS = Instant.parse("2026-09-16T00:00:00Z").getEpochSecond();
    private static final StructType REVIEW_SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType).add("voted_up", DataTypes.BooleanType);
    private static final StructType PATCH_SCHEMA = new StructType()
            .add("gid", DataTypes.StringType).add("appid", DataTypes.LongType)
            .add("patched_ts", DataTypes.LongType).add("eligible_for_review_stats", DataTypes.BooleanType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("patch_stat_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.sql.session.timeZone", "UTC").config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }
    private static Dataset<Row> reviews(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), REVIEW_SCHEMA); }
    private static Dataset<Row> patches(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), PATCH_SCHEMA); }
    private static Row patch(String gid, long app) { return RowFactory.create(gid, app, PATCH_DAY + 12 * 3600L, true); }
    private static Row review(long app, long id, long updated, long collected, boolean positive) {
        return RowFactory.create(app, id, TimeRule.startOfDay(START.minusDays(5)), updated, collected, positive);
    }
    private static Row review(long id, long updated, boolean positive) { return review(1, id, updated, updated + 1, positive); }
    private static List<Row> result(Dataset<Row> reviews, Dataset<Row> patches) {
        return PatchStatAggregator.aggregate(reviews, patches, START, END, Instant.ofEpochSecond(RUN_TS))
                .orderBy("gid").collectAsList();
    }
    private static void rate(Row row, String name, String expected) {
        assertEquals(new BigDecimal(expected), row.getAs(name));
    }

    @Test void kstWindowsUseLastRevisionSeparatelyAndExcludeOutsideVersions() {
        long start = TimeRule.startOfDay(START);
        long end = TimeRule.startOfDay(END);
        Dataset<Row> input = reviews(review(1, start, true), review(1, PATCH_DAY - 100, false),
                review(2, start - 1, true), review(3, PATCH_DAY - 1, false),
                review(3, PATCH_DAY + 1, true), review(4, PATCH_DAY, true),
                review(5, end - 1, false), review(6, end, true),
                review(1, 4, PATCH_DAY + 2, RUN_TS + 1, false),
                review(2, 70, PATCH_DAY, PATCH_DAY + 1, false));
        Row row = result(input.union(input).repartition(2), patches(patch("100", 1))).get(0);
        assertEquals(2, (int) row.getAs("before_review_count"));
        assertEquals(3, (int) row.getAs("after_review_count"));
        rate(row, "before_positive_pct", "0.00");
        rate(row, "after_positive_pct", "66.67");
        rate(row, "delta_pct", "66.67");
        assertEquals("2026-09-16", row.getAs("stat_date").toString());
    }

    @Test void noObservedOriginalVoteIsInventedAndEmptyPeriodIsNull() {
        Row row = result(reviews(review(1, PATCH_DAY + 20, false)), patches(patch("100", 1))).get(0);
        assertEquals(0, (int) row.getAs("before_review_count"));
        assertNull(row.getAs("before_positive_pct"));
        assertNull(row.getAs("delta_pct"));
        rate(row, "after_positive_pct", "0.00");
    }

    @Test void emptyCompleteCoverageStillHasZeroCountsPerApprovedPatch() {
        List<Row> rows = result(reviews(), patches(patch("100", 1), patch("200", 2)));
        assertEquals(2, rows.size());
        for (Row row : rows) {
            assertEquals(0, (int) row.getAs("after_review_count"));
            assertNull(row.getAs("after_positive_pct"));
        }
    }

    @Test void incompleteOrUnapprovedPatchesDoNotBecomeCompletedStats() {
        Dataset<Row> candidates = patches(patch("100", 1), RowFactory.create("200", 1L, PATCH_DAY, false),
                RowFactory.create("300", 1L, null, true), RowFactory.create("400", 1L, PATCH_DAY, null));
        assertEquals(1, result(reviews(), candidates).size());
        assertTrue(PatchStatAggregator.aggregate(reviews(), candidates, START.plusDays(1), END,
                Instant.ofEpochSecond(RUN_TS)).collectAsList().isEmpty());
        assertTrue(PatchStatAggregator.aggregate(reviews(), candidates, START, END,
                Instant.ofEpochSecond(PATCH_DAY + 3 * 86400L)).collectAsList().isEmpty());
    }

    @Test void overlappingPatchesAreIndependentCasesAndInputDuplicatesAreIdempotent(@TempDir Path directory) {
        Dataset<Row> input = reviews(review(1, PATCH_DAY, true));
        String path = directory.resolve("reviews").toString();
        input.write().parquet(path);
        Dataset<Row> candidates = patches(patch("100", 1), patch("100", 1), patch("200", 1));
        List<Row> rows = result(spark.read().parquet(path), candidates);
        assertEquals(2, rows.size());
        for (Row row : rows) assertEquals(1, (int) row.getAs("after_review_count"));
        assertEquals(rows, result(input.union(input), candidates));
    }

    @Test void unroundedRatesAreSubtractedBeforeRoundingDelta() {
        List<Row> input = new ArrayList<>();
        for (int index = 0; index < 6; index++) input.add(review(index + 1, PATCH_DAY - 10, index == 0));
        for (int index = 0; index < 12; index++) input.add(review(index + 10, PATCH_DAY + 10, index == 0));
        Row row = result(reviews(input.toArray(Row[]::new)), patches(patch("100", 1))).get(0);
        rate(row, "before_positive_pct", "16.67");
        rate(row, "after_positive_pct", "8.33");
        rate(row, "delta_pct", "-8.33");
    }

    @Test void newerModificationWinsEvenIfOldVersionCollectedLater() {
        Row row = result(reviews(review(1, 1, PATCH_DAY + 1, PATCH_DAY + 100, true),
                review(1, 1, PATCH_DAY + 2, PATCH_DAY + 3, false)), patches(patch("100", 1))).get(0);
        rate(row, "after_positive_pct", "0.00");
        Row repeated = result(reviews(review(1, 1, PATCH_DAY, PATCH_DAY + 1, true),
                review(1, 1, PATCH_DAY, PATCH_DAY + 2, false)), patches(patch("100", 1))).get(0);
        rate(repeated, "after_positive_pct", "0.00");
    }

    @Test void invalidTypesConflictsAndTimestampOrderFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> result(reviews(), patches(patch("100", 1), patch("100", 2))));
        assertThrows(IllegalArgumentException.class, () -> result(reviews(review(1, PATCH_DAY, true), review(1, PATCH_DAY, false)), patches(patch("100", 1))));
        assertThrows(IllegalArgumentException.class, () -> result(reviews(review(1, 1, PATCH_DAY, PATCH_DAY - 1, true)), patches(patch("100", 1))));
        assertThrows(IllegalArgumentException.class, () -> result(reviews(), patches(patch("100", 1)).withColumn("patched_ts", lit("wrong"))));
        assertThrows(IllegalArgumentException.class, () -> PatchStatAggregator.aggregate(reviews(), patches(), END, START, Instant.now()));
    }
}
