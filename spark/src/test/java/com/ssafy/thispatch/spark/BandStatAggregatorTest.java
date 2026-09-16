package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.SparkSessions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.apache.spark.sql.functions.lit;
import static org.junit.jupiter.api.Assertions.*;

class BandStatAggregatorTest {
    private static SparkSession spark;
    private static final Instant RUN_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final StructType SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType).add("voted_up", DataTypes.BooleanType)
            .add("playtime_at_review", DataTypes.IntegerType);

    @BeforeAll
    static void startSpark() {
        spark = SparkSessions.builder("band_stat_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }

    @AfterAll
    static void stopSpark() {
        if (spark != null) {
            spark.stop();
        }
    }

    private static Row review(long appid, long reviewId, Integer minutes, boolean positive) {
        return RowFactory.create(appid, reviewId, 1L, 1L, 2L, positive, minutes);
    }

    private static Dataset<Row> input(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), SCHEMA);
    }

    private static List<Row> bands(Dataset<Row> reviews) {
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(reviews);
        return BandStatAggregator.aggregateLatestReviews(latest, RUN_AT)
                .orderBy("appid", "band_no").collectAsList();
    }

    private static void assertBand(Row band, int number, int from, Integer to, long count, long positive) {
        assertEquals((short) number, (short) band.getAs("band_no"));
        assertEquals(from, (int) band.getAs("playtime_from"));
        assertEquals(to, band.getAs("playtime_to"));
        assertEquals(count, (long) band.getAs("review_count"));
        assertEquals(positive, (long) band.getAs("positive_count"));
        assertEquals(RUN_AT, ((java.sql.Timestamp) band.getAs("aggregated_at")).toInstant());
    }

    @Test
    void eightDistinctTimesProduceFourBandsWithExactCounts() {
        List<Row> result = bands(input(
                review(1, 1, 10, true), review(1, 2, 20, false),
                review(1, 3, 30, true), review(1, 4, 40, true),
                review(1, 5, 50, false), review(1, 6, 60, false),
                review(1, 7, 70, true), review(1, 8, 80, false)));
        assertEquals(4, result.size());
        assertBand(result.get(0), 1, 0, 20, 1, 1);
        assertBand(result.get(1), 2, 20, 40, 2, 1);
        assertBand(result.get(2), 3, 40, 60, 2, 1);
        assertBand(result.get(3), 4, 60, null, 3, 1);
    }

    @Test
    void tiedQuartilesKeepFourBandsWithoutSplittingEqualPlaytimes() {
        List<Row> result = bands(input(
                review(1, 1, 10, true), review(1, 2, 10, false),
                review(1, 3, 10, true), review(1, 4, 10, false),
                review(1, 5, 10, true), review(1, 6, 10, false),
                review(1, 7, 20, true), review(1, 8, 30, false)));
        assertEquals(4, result.size());
        assertBand(result.get(0), 1, 0, 10, 0, 0);
        assertBand(result.get(1), 2, 10, 10, 0, 0);
        assertBand(result.get(2), 3, 10, 10, 0, 0);
        assertBand(result.get(3), 4, 10, null, 8, 4);
    }

    @Test
    void allEqualTimesKeepEmptyBandsAndPlaceReviewsInFourthBand() {
        List<Row> result = bands(input(review(1, 1, 100, true), review(1, 2, 100, false)));
        assertEquals(4, result.size());
        assertBand(result.get(0), 1, 0, 100, 0, 0);
        assertBand(result.get(1), 2, 100, 100, 0, 0);
        assertBand(result.get(2), 3, 100, 100, 0, 0);
        assertBand(result.get(3), 4, 100, null, 2, 1);
    }

    @Test
    void smallGamesKeepFourBandsAndIndependentThresholds() {
        List<Row> result = bands(input(review(1, 1, 10, true), review(1, 2, 20, false),
                review(1, 3, 30, true), review(2, 4, 1000, true)));
        assertEquals(8, result.size());
        assertBand(result.get(0), 1, 0, 10, 0, 0);
        assertBand(result.get(1), 2, 10, 20, 1, 1);
        assertBand(result.get(2), 3, 20, 30, 1, 0);
        assertBand(result.get(3), 4, 30, null, 1, 1);
        assertEquals(2L, (long) result.get(4).getAs("appid"));
        assertBand(result.get(4), 1, 0, 1000, 0, 0);
        assertBand(result.get(5), 2, 1000, 1000, 0, 0);
        assertBand(result.get(6), 3, 1000, 1000, 0, 0);
        assertBand(result.get(7), 4, 1000, null, 1, 1);
    }

    @Test
    void newerModificationWinsOverLaterCollectionOfOldVersion() {
        List<Row> result = bands(input(
                RowFactory.create(1L, 1L, 1L, 3L, 4L, false, 20),
                RowFactory.create(1L, 1L, 1L, 1L, 5L, true, 10)));
        assertEquals(4, result.size());
        assertBand(result.get(3), 4, 20, null, 1, 0);
    }

    @Test
    void latestCollectionBreaksVersionTieAndMissingPlaytimeDoesNotFallBack() {
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(input(
                RowFactory.create(1L, 1L, 1L, 1L, 2L, true, 10),
                RowFactory.create(1L, 1L, 1L, 1L, 3L, false, null)));
        Row selected = latest.first();
        assertNull(selected.getAs("playtime_at_review"));
        assertFalse((boolean) selected.getAs("voted_up"));
        assertEquals(1L, (long) BandStatAggregator.qualityCounts(latest).first().getAs("missing_playtime_count"));
        assertTrue(BandStatAggregator.aggregateLatestReviews(latest, RUN_AT).isEmpty());
    }

    @Test
    void missingAndNegativeTimesAreExcludedButZeroIsIncluded() {
        Dataset<Row> source = input(review(1, 1, null, true), review(1, 2, -1, false),
                review(1, 3, 0, true), review(2, 4, null, false));
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(source);
        List<Row> quality = BandStatAggregator.qualityCounts(latest).orderBy("appid").collectAsList();
        assertEquals(3L, (long) quality.get(0).getAs("latest_review_count"));
        assertEquals(1L, (long) quality.get(0).getAs("missing_playtime_count"));
        assertEquals(1L, (long) quality.get(0).getAs("negative_playtime_count"));
        assertEquals(1L, (long) quality.get(0).getAs("included_review_count"));
        assertEquals(0L, (long) quality.get(1).getAs("included_review_count"));
        List<Row> result = bands(source);
        assertEquals(4, result.size());
        assertBand(result.get(0), 1, 0, 0, 0, 0);
        assertBand(result.get(1), 2, 0, 0, 0, 0);
        assertBand(result.get(2), 3, 0, 0, 0, 0);
        assertBand(result.get(3), 4, 0, null, 1, 1);
    }

    @Test
    void repeatedRecordsAndInputOrderDoNotChangeResults() {
        Dataset<Row> source = input(review(1, 1, 10, true), review(1, 2, 20, false));
        assertEquals(bands(source), bands(source.union(source).repartition(2)));
    }

    @Test
    void totalPlaytimeIsNotUsedAndParquetRoundTripPreservesResults(@TempDir Path directory) {
        Dataset<Row> source = input(review(1, 1, 10, true), review(1, 2, 20, false))
                .withColumn("playtime_forever", lit(999999));
        String path = directory.resolve("band-reviews").toString();
        source.write().parquet(path);
        List<Row> result = bands(spark.read().parquet(path));
        assertEquals(bands(source), result);
        assertBand(result.get(2), 3, 10, 20, 1, 1);
    }

    @Test
    void emptyInputAndMaximumIntegerTimeAreSafe() {
        assertTrue(bands(input()).isEmpty());
        List<Row> result = bands(input(review(1, 1, Integer.MAX_VALUE - 1, true),
                review(1, 2, Integer.MAX_VALUE, false)));
        assertEquals(4, result.size());
        assertBand(result.get(0), 1, 0, Integer.MAX_VALUE - 1, 0, 0);
        assertBand(result.get(1), 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1, 0, 0);
        assertBand(result.get(2), 3, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, 1);
        assertBand(result.get(3), 4, Integer.MAX_VALUE, null, 1, 0);
    }

    @Test
    void invalidRequiredValuesAndWrongTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> bands(input(
                RowFactory.create(1L, 1L, 5L, 1L, 2L, true, 10))));
        assertThrows(IllegalArgumentException.class, () -> bands(input(
                RowFactory.create(1L, 1L, 1L, 1L, 2L, null, 10))));
        assertThrows(IllegalArgumentException.class, () -> bands(input(review(1, 1, 10, true))
                .withColumn("playtime_at_review", lit("10"))));
    }

    @Test
    void conflictingSameTimestampPlaytimesAreRejectedIncludingNull() {
        assertThrows(IllegalArgumentException.class, () -> bands(input(
                review(1, 1, 10, true), review(1, 1, 20, true))));
        assertThrows(IllegalArgumentException.class, () -> bands(input(
                review(1, 1, 10, true), review(1, 1, null, true))));
    }
}
