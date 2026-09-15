package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.ReviewSchema;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;

import static org.apache.spark.sql.functions.*;

/** Counts recommendation flags, not topic-level sentiment. Does not run an AI model. */
public final class BandTopicStatAggregator {
    private BandTopicStatAggregator() {}

    /**
     * latestReviews and bands must come from the same BandStatAggregator run.
     * Topic input is an internal join contract, NOT an agreed HDFS file schema:
     * appid LONG, recommendationid LONG, updated_ts LONG, topic_id SHORT.
     * The producer adapter must select one classification run before invoking this method.
     */
    public static Dataset<Row> aggregateLatestReviews(
            Dataset<Row> latestReviews, Dataset<Row> bands, Dataset<Row> topicAssignments) {
        validateTopicAssignments(topicAssignments);
        Dataset<Row> assignments = topicAssignments
                .select("appid", "recommendationid", "updated_ts", "topic_id")
                .dropDuplicates("appid", "recommendationid", "updated_ts", "topic_id");

        Dataset<Row> reviews = latestReviews.filter(col("playtime_at_review").geq(0))
                .select("appid", "recommendationid", "updated_ts", "voted_up", "playtime_at_review");
        if (reviews.groupBy("appid", "recommendationid").count().filter(col("count").gt(1))
                .limit(1).count() != 0) {
            throw new IllegalArgumentException("Expected one latest row per game/review");
        }
        if (reviews.filter(col("appid").isNull().or(col("recommendationid").isNull())
                .or(col("updated_ts").isNull()).or(col("voted_up").isNull())).limit(1).count() != 0) {
            throw new IllegalArgumentException("Latest reviews contain missing join keys or recommendation flags");
        }

        Dataset<Row> reviewRows = reviews.alias("review");
        Dataset<Row> bandRows = bands.select("appid", "band_no", "playtime_from", "playtime_to").alias("band");
        Column sameGame = col("review.appid").equalTo(col("band.appid"));
        Column withinBand = col("review.playtime_at_review").geq(col("band.playtime_from"))
                .and(col("band.playtime_to").isNull()
                        .or(col("review.playtime_at_review").lt(col("band.playtime_to"))));
        Dataset<Row> assignedReviews = reviewRows.join(bandRows, sameGame.and(withinBand), "left")
                .select(col("review.appid").alias("appid"), col("review.recommendationid").alias("recommendationid"),
                        col("review.updated_ts").alias("updated_ts"), col("review.voted_up").alias("voted_up"),
                        col("band.band_no").alias("band_no"));
        // Overlapping or missing ranges would duplicate or silently lose reviews.
        if (assignedReviews.filter(col("band_no").isNull()).limit(1).count() != 0
                || assignedReviews.groupBy("appid", "recommendationid").count()
                        .filter(col("count").notEqual(1)).limit(1).count() != 0) {
            throw new IllegalArgumentException("Each eligible review must match exactly one band from the same run");
        }

        // Match the review revision as well as its ID: old-body topics must not label a new-body review.
        // Unmatched assignments are not fabricated as zero-label or zero-positive reviews.
        Dataset<Row> mentions = assignedReviews.join(assignments,
                new String[] {"appid", "recommendationid", "updated_ts"}, "inner");
        return mentions.groupBy("appid", "band_no", "topic_id")
                .agg(sum(when(col("voted_up"), 1L).otherwise(0L)).alias("positive_count"),
                        sum(when(col("voted_up").equalTo(false), 1L).otherwise(0L)).alias("negative_count"));
    }

    private static void validateTopicAssignments(Dataset<Row> assignments) {
        for (String name : new String[] {"appid", "recommendationid", "updated_ts"}) {
            if (!assignments.schema().apply(name).dataType().equals(ReviewSchema.REVIEW_RAW.apply(name).dataType())) {
                throw new IllegalArgumentException("Unexpected topic join key type: " + name);
            }
        }
        if (!assignments.schema().apply("topic_id").dataType().equals(DataTypes.ShortType)) {
            throw new IllegalArgumentException("topic_id must match the database SMALLINT type");
        }
        Column invalid = col("appid").isNull().or(col("recommendationid").isNull())
                .or(col("updated_ts").isNull()).or(col("topic_id").isNull())
                .or(col("appid").leq(0)).or(col("recommendationid").leq(0)).or(col("updated_ts").lt(0));
        if (assignments.filter(invalid).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid topic assignment join keys");
        }
    }
}
