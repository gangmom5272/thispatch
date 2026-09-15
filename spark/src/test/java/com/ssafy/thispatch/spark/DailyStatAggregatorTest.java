package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.SparkSessions;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyStatAggregatorTest {
    private static SparkSession spark;
    private static final Instant RUN_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final StructType SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType).add("voted_up", DataTypes.BooleanType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("daily_stat_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row review(long app, long id, String created, String updated, String collected, boolean vote) {
        return RowFactory.create(app, id, Instant.parse(created).getEpochSecond(),
                Instant.parse(updated).getEpochSecond(), Instant.parse(collected).getEpochSecond(), vote);
    }
    private static Dataset<Row> input(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), SCHEMA); }
    private static List<Row> result(Dataset<Row> input) {
        return DailyStatAggregator.aggregate(input, RUN_AT).orderBy("appid", "stat_date").collectAsList();
    }
    private static void counts(Row row, long total, long fresh, long freshPositive, long edited, long editedPositive, long negative) {
        assertEquals(total, (long) row.getAs("review_count"));
        assertEquals(fresh, (long) row.getAs("new_review_count"));
        assertEquals(freshPositive, (long) row.getAs("new_positive_count"));
        assertEquals(edited, (long) row.getAs("edited_review_count"));
        assertEquals(editedPositive, (long) row.getAs("edited_positive_count"));
        assertEquals(negative, (long) row.getAs("negative_count"));
        assertEquals(total, fresh + edited);
        assertEquals(total, freshPositive + editedPositive + negative);
    }
    @Test void sameDayEditsBecomeOneNewReviewWithLatestVote() {
        List<Row> rows = result(input(
                review(1, 11, "2026-09-10T16:00:00Z", "2026-09-10T16:00:00Z", "2026-09-10T17:00:00Z", true),
                review(1, 11, "2026-09-10T16:00:00Z", "2026-09-10T18:00:00Z", "2026-09-10T19:00:00Z", false)));
        assertEquals(1, rows.size());
        assertEquals("2026-09-11", rows.get(0).getAs("stat_date").toString());
        counts(rows.get(0), 1, 1, 0, 0, 0, 1);
    }
    @Test void historicalVersionsRemainAndRepeatedCollectionIsNotNewActivity() {
        List<Row> rows = result(input(
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", true),
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T00:00:00Z", "2026-09-05T01:00:00Z", true),
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T02:00:00Z", "2026-09-05T03:00:00Z", false),
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T02:00:00Z", "2026-09-11T03:00:00Z", false)));
        assertEquals(2, rows.size());
        counts(rows.get(0), 1, 1, 1, 0, 0, 0);
        counts(rows.get(1), 1, 0, 0, 1, 0, 1);
    }
    @Test void kstMidnightNotUtcSeparatesDays() {
        List<Row> rows = result(input(
                review(1, 11, "2026-09-10T14:59:59Z", "2026-09-10T14:59:59Z", "2026-09-10T15:01:00Z", true),
                review(1, 12, "2026-09-10T15:00:00Z", "2026-09-10T15:00:00Z", "2026-09-10T15:01:00Z", true)));
        assertEquals(List.of("2026-09-10", "2026-09-11"), rows.stream().map(r -> r.getAs("stat_date").toString()).toList());
    }
    @Test void unseenOriginalVoteIsNotInvented() {
        List<Row> rows = result(input(review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T00:00:00Z", "2026-09-11T00:00:00Z", false)));
        assertEquals(1, rows.size());
        assertEquals("2026-09-05", rows.get(0).getAs("stat_date").toString());
        counts(rows.get(0), 1, 0, 0, 1, 0, 1);
    }
    @Test void duplicatedInputAndRerunsProduceSameCounts() {
        Dataset<Row> reviews = input(review(1, 11, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", true));
        assertEquals(result(reviews), result(reviews.union(reviews).repartition(2)));
    }
    @Test void latestModificationWinsEvenIfOlderVersionWasCollectedLater() {
        List<Row> rows = result(input(
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T02:00:00Z", "2026-09-05T03:00:00Z", false),
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-05T00:00:00Z", "2026-09-06T03:00:00Z", true)));
        counts(rows.get(0), 1, 0, 0, 1, 0, 1);
    }
    @Test void latestCollectionResolvesRepeatedVersion() {
        List<Row> rows = result(input(RowFactory.create(1L, 11L, 1L, 1L, 2L, true),
                RowFactory.create(1L, 11L, 1L, 1L, 3L, false)));
        counts(rows.get(0), 1, 1, 0, 0, 0, 1);
    }
    @Test void stringTimestampIsRejectedInsteadOfSilentlyCast() {
        Dataset<Row> wrongType = input(RowFactory.create(1L, 11L, 1L, 1L, 2L, true))
                .withColumn("created_ts", org.apache.spark.sql.functions.lit("invalid"));
        assertThrows(IllegalArgumentException.class, () -> result(wrongType));
    }
    @Test void parquetRoundTripAndDifferentGames(@TempDir Path directory) {
        Dataset<Row> reviews = input(
                review(1, 11, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", true),
                review(2, 12, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", false));
        String path = directory.resolve("reviews").toString();
        reviews.write().parquet(path);
        List<Row> actual = result(spark.read().parquet(path));
        assertEquals(2, actual.size());
        assertEquals(result(reviews), actual);
    }
    @Test void emptyInputProducesNoRows() { assertTrue(result(input()).isEmpty()); }
    @Test void invalidTimestampAndNullVoteFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> result(input(
                review(1, 11, "2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-03T00:00:00Z", true))));
        assertThrows(IllegalArgumentException.class, () -> result(input(RowFactory.create(1L, 11L, 1L, 1L, 2L, null))));
    }
    @Test void identicalTimestampConflictsFailInsteadOfPickingRandomVote() {
        assertThrows(IllegalArgumentException.class, () -> result(input(
                RowFactory.create(1L, 11L, 1L, 1L, 2L, true), RowFactory.create(1L, 11L, 1L, 1L, 2L, false))));
    }
}
