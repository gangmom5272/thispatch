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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.apache.spark.sql.functions.lit;
import static org.junit.jupiter.api.Assertions.*;

class LanguageStatAggregatorTest {
    private static SparkSession spark;
    private static final Instant RUN_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final StructType SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType).add("voted_up", DataTypes.BooleanType)
            .add("language_code", DataTypes.StringType);

    @BeforeAll
    static void startSpark() {
        spark = SparkSessions.builder("language_stat_test").master("local[2]")
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

    private static Row review(long appid, long reviewId, String language, boolean positive) {
        return RowFactory.create(appid, reviewId, 1L, 1L, 2L, positive, language);
    }

    private static Dataset<Row> input(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), SCHEMA);
    }

    private static List<Row> result(Dataset<Row> reviews) {
        return LanguageStatAggregator.aggregate(reviews, RUN_AT)
                .orderBy("appid", "language_code").collectAsList();
    }

    private static void assertCounts(Row row, long appid, String language, long total, long positive) {
        assertEquals(appid, (long) row.getAs("appid"));
        assertEquals(language, row.getAs("language_code"));
        assertEquals(total, (long) row.getAs("review_count"));
        assertEquals(positive, (long) row.getAs("positive_count"));
        assertEquals(RUN_AT, ((Timestamp) row.getAs("aggregated_at")).toInstant());
    }

    @Test
    void gamesAndLanguagesAreCountedSeparately() {
        List<Row> rows = result(input(review(1, 1, "koreana", true), review(1, 2, "koreana", false),
                review(1, 3, "english", true), review(2, 4, "koreana", false)));
        assertEquals(3, rows.size());
        assertCounts(rows.get(0), 1, "english", 1, 1);
        assertCounts(rows.get(1), 1, "koreana", 2, 1);
        assertCounts(rows.get(2), 2, "koreana", 1, 0);
    }

    @Test
    void languageChangeMovesReviewRatherThanCountingBothVersions() {
        List<Row> rows = result(input(review(1, 1, "english", true),
                RowFactory.create(1L, 1L, 1L, 3L, 4L, false, "koreana")));
        assertEquals(1, rows.size());
        assertCounts(rows.get(0), 1, "koreana", 1, 0);
    }

    @Test
    void oldVersionCollectedLaterDoesNotReplaceLatestModification() {
        List<Row> rows = result(input(
                RowFactory.create(1L, 1L, 1L, 1L, 10L, true, "english"),
                RowFactory.create(1L, 1L, 1L, 3L, 4L, false, "koreana")));
        assertEquals(1, rows.size());
        assertCounts(rows.get(0), 1, "koreana", 1, 0);
    }

    @Test
    void latestCollectionBreaksModificationTimestampTie() {
        List<Row> rows = result(input(review(1, 1, "english", true),
                RowFactory.create(1L, 1L, 1L, 1L, 3L, false, "koreana")));
        assertEquals(1, rows.size());
        assertCounts(rows.get(0), 1, "koreana", 1, 0);
    }

    @Test
    void duplicateInputAndRepartitionDoNotChangeCounts() {
        Dataset<Row> source = input(review(1, 1, "english", true), review(1, 2, "koreana", false));
        assertEquals(result(source), result(source.union(source).repartition(2)));
    }

    @Test
    void emptyInputProducesNoRows() {
        assertTrue(result(input()).isEmpty());
    }

    @Test
    void malformedLanguageCodesFailWithoutSilentlyDroppingReviews() {
        for (String language : new String[] {null, "", " ", " english", "english ", "a".repeat(21)}) {
            assertThrows(IllegalArgumentException.class, () -> result(input(review(1, 1, language, true))));
        }
    }

    @Test
    void languageCodesArePreservedWithoutAnInventedAllowlist() {
        List<Row> rows = result(input(review(1, 1, "new_language", true)));
        assertCounts(rows.get(0), 1, "new_language", 1, 1);
    }

    @Test
    void conflictingLanguageOrVoteAtSameTimestampsFails() {
        assertThrows(IllegalArgumentException.class, () -> result(input(
                review(1, 1, "english", true), review(1, 1, "koreana", true))));
        assertThrows(IllegalArgumentException.class, () -> result(input(
                review(1, 1, "english", true), review(1, 1, "english", false))));
    }

    @Test
    void requiredFieldsAndTypesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> result(input(
                RowFactory.create(1L, 1L, 5L, 1L, 2L, true, "english"))));
        assertThrows(IllegalArgumentException.class, () -> result(input(
                RowFactory.create(1L, 1L, 1L, 1L, 2L, null, "english"))));
        assertThrows(IllegalArgumentException.class, () -> result(input(review(1, 1, "english", true))
                .withColumn("language_code", lit(1))));
    }

    @Test
    void parquetRoundTripPreservesResultsWithoutPlaytimeFields(@TempDir Path directory) {
        Dataset<Row> source = input(review(1, 1, "english", true), review(1, 2, "koreana", false));
        String path = directory.resolve("language-reviews").toString();
        source.write().parquet(path);
        assertEquals(result(source), result(spark.read().parquet(path)));
    }
}
