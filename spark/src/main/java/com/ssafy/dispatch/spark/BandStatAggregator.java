package com.ssafy.dispatch.spark;

import com.ssafy.dispatch.common.ReviewSchema;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

import static org.apache.spark.sql.functions.*;

/** Current observed review distribution, not a daily activity count. Playtime is in minutes. */
public final class BandStatAggregator {
    private static final String[] INPUT_COLUMNS = {
        "appid", "recommendationid", "created_ts", "updated_ts", "collected_ts",
        "voted_up", "playtime_at_review"
    };

    private BandStatAggregator() {}

    public static Dataset<Row> selectLatestReviews(Dataset<Row> reviews) {
        for (String name : INPUT_COLUMNS) {
            if (!reviews.schema().apply(name).dataType().equals(ReviewSchema.REVIEW_RAW.apply(name).dataType())) {
                throw new IllegalArgumentException("Unexpected input type: " + name);
            }
        }
        Column[] columns = Arrays.stream(INPUT_COLUMNS).map(org.apache.spark.sql.functions::col)
                .toArray(Column[]::new);
        Dataset<Row> input = reviews.select(columns);
        // This checks identity, vote and timestamp validity, but allows missing playtime.
        if (input.filter(DailyStatAggregator.invalidInput()).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid required fields or timestamp order");
        }
        Dataset<Row> conflicts = input.groupBy("appid", "recommendationid", "updated_ts", "collected_ts")
                .agg(countDistinct(struct(col("created_ts"), col("voted_up"), col("playtime_at_review")))
                        .alias("variants"))
                .filter(col("variants").gt(1));
        if (conflicts.limit(1).count() != 0) {
            throw new IllegalArgumentException("Conflicting observations at identical timestamps");
        }
        // Latest modification wins even if an older version arrived later.
        // Do not fall back to an older positive vote or non-null playtime.
        return input.withColumn("latest_rank", row_number().over(
                        Window.partitionBy("appid", "recommendationid")
                                .orderBy(col("updated_ts").desc(), col("collected_ts").desc())))
                .filter(col("latest_rank").equalTo(1)).drop("latest_rank");
    }

    /** Call after selectLatestReviews: exclusions are counts of reviews, not collection records. */
    public static Dataset<Row> qualityCounts(Dataset<Row> latestReviews) {
        return latestReviews.groupBy("appid").agg(
                count(lit(1)).alias("latest_review_count"),
                sum(when(col("playtime_at_review").isNull(), 1L).otherwise(0L)).alias("missing_playtime_count"),
                sum(when(col("playtime_at_review").lt(0), 1L).otherwise(0L)).alias("negative_playtime_count"),
                sum(when(col("playtime_at_review").geq(0), 1L).otherwise(0L)).alias("included_review_count"));
    }

    /** Input must be the output of selectLatestReviews. No data is collected into the driver. */
    public static Dataset<Row> aggregateLatestReviews(Dataset<Row> latestReviews, Instant aggregatedAt) {
        // Aggregate equal playtimes before sorting: ties stay together and fewer rows are shuffled.
        Dataset<Row> histogram = latestReviews.filter(col("playtime_at_review").geq(0))
                .groupBy("appid", "playtime_at_review")
                .agg(count(lit(1)).alias("review_count"),
                        sum(when(col("voted_up"), 1L).otherwise(0L)).alias("positive_count"));
        WindowSpec game = Window.partitionBy("appid");
        WindowSpec orderedTimes = game.orderBy("playtime_at_review")
                .rowsBetween(Window.unboundedPreceding(), Window.currentRow());
        Dataset<Row> distribution = histogram
                .withColumn("game_count", sum("review_count").over(game))
                .withColumn("cumulative_count", sum("review_count").over(orderedTimes));

        // Exact nearest-rank quartiles: the first observed minute reaching ceil(N * p).
        Dataset<Row> thresholds = distribution.groupBy("appid").agg(
                min(when(col("cumulative_count").geq(ceil(col("game_count").multiply(0.25))),
                        col("playtime_at_review"))).alias("q1"),
                min(when(col("cumulative_count").geq(ceil(col("game_count").multiply(0.50))),
                        col("playtime_at_review"))).alias("q2"),
                min(when(col("cumulative_count").geq(ceil(col("game_count").multiply(0.75))),
                        col("playtime_at_review"))).alias("q3"));
        Dataset<Row> grouped = histogram.join(thresholds, "appid")
                .withColumn("quartile", when(col("playtime_at_review").leq(col("q1")), 1)
                        .when(col("playtime_at_review").leq(col("q2")), 2)
                        .when(col("playtime_at_review").leq(col("q3")), 3).otherwise(4))
                .groupBy("appid", "quartile")
                .agg(sum("review_count").alias("review_count"),
                        sum("positive_count").alias("positive_count"),
                        max("playtime_at_review").alias("last_minute"));

        // Empty quartiles are merged; boundaries are [from, to), with a null final upper bound.
        // Use long arithmetic before casting so a maximum INT minute cannot overflow internally.
        WindowSpec orderedBands = game.orderBy("quartile");
        return grouped
                .withColumn("band_no", row_number().over(orderedBands).cast(DataTypes.ShortType))
                .withColumn("playtime_from", coalesce(lag(col("last_minute").cast(DataTypes.LongType), 1)
                        .over(orderedBands).plus(1L), lit(0L)).cast(DataTypes.IntegerType))
                .withColumn("playtime_to", when(lead(col("quartile"), 1).over(orderedBands).isNotNull(),
                        col("last_minute").cast(DataTypes.LongType).plus(1L)).cast(DataTypes.IntegerType))
                .withColumn("aggregated_at", lit(Timestamp.from(aggregatedAt)))
                .select("appid", "band_no", "playtime_from", "playtime_to", "review_count",
                        "positive_count", "aggregated_at");
    }
}
