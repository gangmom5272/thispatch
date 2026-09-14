package com.ssafy.dispatch.spark;

import com.ssafy.dispatch.common.SparkSessions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.apache.spark.sql.functions.lit;
import static org.junit.jupiter.api.Assertions.*;

class BandTopicStatAggregatorTest {
    private static SparkSession spark;
    private static final Instant RUN_AT = Instant.parse("2026-09-14T00:00:00Z");
    private static final StructType REVIEW_SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("created_ts", DataTypes.LongType).add("updated_ts", DataTypes.LongType)
            .add("collected_ts", DataTypes.LongType).add("voted_up", DataTypes.BooleanType)
            .add("playtime_at_review", DataTypes.IntegerType);
    private static final StructType TOPIC_SCHEMA = new StructType()
            .add("appid", DataTypes.LongType).add("recommendationid", DataTypes.LongType)
            .add("updated_ts", DataTypes.LongType).add("topic_id", DataTypes.ShortType);

    @BeforeAll
    static void startSpark() {
        spark = SparkSessions.builder("band_topic_stat_test").master("local[2]")
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

    private static Row topic(long appid, long reviewId, long updatedTs, int topicId) {
        return RowFactory.create(appid, reviewId, updatedTs, (short) topicId);
    }

    private static Dataset<Row> reviews(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), REVIEW_SCHEMA);
    }

    private static Dataset<Row> topics(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), TOPIC_SCHEMA);
    }

    private static List<Row> result(Dataset<Row> source, Dataset<Row> assignments) {
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(source);
        Dataset<Row> bands = BandStatAggregator.aggregateLatestReviews(latest, RUN_AT);
        return BandTopicStatAggregator.aggregateLatestReviews(latest, bands, assignments)
                .orderBy("appid", "band_no", "topic_id").collectAsList();
    }

    private static void assertCounts(Row row, long positive, long negative) {
        assertEquals(positive, (long) row.getAs("positive_count"));
        assertEquals(negative, (long) row.getAs("negative_count"));
    }

    @Test
    void bothRecommendationFlagsAreCountedWithinSameTopic() {
        List<Row> rows = result(reviews(review(1, 1, 100, true), review(1, 2, 100, false)),
                topics(topic(1, 1, 1, 1), topic(1, 2, 1, 1)));
        assertEquals(1, rows.size());
        assertCounts(rows.get(0), 1, 1);
    }

    @Test
    void duplicateLabelsDoNotMultiplyCountsButDifferentTopicsEachCountOnce() {
        List<Row> rows = result(reviews(review(1, 1, 100, true)), topics(
                topic(1, 1, 1, 1), topic(1, 1, 1, 1), topic(1, 1, 1, 2)));
        assertEquals(2, rows.size());
        assertCounts(rows.get(0), 1, 0);
        assertCounts(rows.get(1), 1, 0);
    }

    @Test
    void oldBodyTopicsDoNotMatchTheNewReviewVersion() {
        Dataset<Row> source = reviews(review(1, 1, 100, true),
                RowFactory.create(1L, 1L, 1L, 3L, 4L, false, 100));
        List<Row> rows = result(source, topics(topic(1, 1, 1, 1), topic(1, 1, 3, 2)));
        assertEquals(1, rows.size());
        assertEquals((short) 2, (short) rows.get(0).getAs("topic_id"));
        assertCounts(rows.get(0), 0, 1);
    }

    @Test
    void unmatchedReviewsAndStaleAssignmentsDoNotCreateFabricatedTopicRows() {
        List<Row> rows = result(reviews(review(1, 1, 100, false)),
                topics(topic(1, 2, 1, 1), topic(2, 1, 1, 1)));
        assertTrue(rows.isEmpty());
    }

    @Test
    void gamesAndSharedBandBoundariesStaySeparate() {
        List<Row> rows = result(reviews(review(1, 1, 10, true), review(1, 2, 20, false),
                review(2, 3, 10, false)),
                topics(topic(1, 1, 1, 1), topic(1, 2, 1, 1), topic(2, 3, 1, 1)));
        assertEquals(3, rows.size());
        assertEquals((short) 1, (short) rows.get(0).getAs("band_no"));
        assertEquals((short) 2, (short) rows.get(1).getAs("band_no"));
        assertEquals(2L, (long) rows.get(2).getAs("appid"));
        assertCounts(rows.get(0), 1, 0);
        assertCounts(rows.get(1), 0, 1);
        assertCounts(rows.get(2), 0, 1);
    }

    @Test
    void missingAndNegativePlaytimeFollowBandStatExclusions() {
        List<Row> rows = result(reviews(review(1, 1, null, true), review(1, 2, -1, false),
                review(1, 3, 0, true)), topics(topic(1, 1, 1, 1), topic(1, 2, 1, 1), topic(1, 3, 1, 1)));
        assertEquals(1, rows.size());
        assertCounts(rows.get(0), 1, 0);
    }

    @Test
    void overlappingOrMissingBandsFailInsteadOfMiscounting() {
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(reviews(review(1, 1, 10, true)));
        Dataset<Row> bands = BandStatAggregator.aggregateLatestReviews(latest, RUN_AT);
        Dataset<Row> assignments = topics(topic(1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BandTopicStatAggregator.aggregateLatestReviews(latest, bands.union(bands), assignments));
        assertThrows(IllegalArgumentException.class, () ->
                BandTopicStatAggregator.aggregateLatestReviews(latest, bands.filter(lit(false)), assignments));
    }

    @Test
    void duplicateLatestReviewsFailExplicitly() {
        Dataset<Row> latest = BandStatAggregator.selectLatestReviews(reviews(review(1, 1, 10, true)));
        Dataset<Row> bands = BandStatAggregator.aggregateLatestReviews(latest, RUN_AT);
        assertThrows(IllegalArgumentException.class, () -> BandTopicStatAggregator.aggregateLatestReviews(
                latest.union(latest), bands, topics(topic(1, 1, 1, 1))));
    }

    @Test
    void invalidTopicKeysAndTypesFailExplicitly() {
        Dataset<Row> source = reviews(review(1, 1, 10, true));
        assertThrows(IllegalArgumentException.class, () -> result(source,
                topics(RowFactory.create(1L, 1L, 1L, null))));
        assertThrows(IllegalArgumentException.class, () -> result(source,
                topics(topic(1, 1, 1, 1)).withColumn("topic_id", lit("1"))));
    }

    @Test
    void emptyInputAndUnclassifiedInputProduceNoTopicRows() {
        assertTrue(result(reviews(), topics()).isEmpty());
        assertTrue(result(reviews(review(1, 1, 10, true)), topics()).isEmpty());
    }
}
