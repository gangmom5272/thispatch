package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.types.DataTypes;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import static org.apache.spark.sql.functions.*;

/** KST calendar-day comparison of observed review activity, not a causal estimate or a snapshot of all reviews. */
public final class PatchStatAggregator {
    private static final long SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60;

    private PatchStatAggregator() {}

    /**
     * Internal patch input: gid STRING, appid LONG, published_ts LONG (nullable), eligible_for_review_stats BOOLEAN.
     * Eligibility describes patch relevance and scope, not deployment-date verification.
     * coverageStart/End describe COMPLETE review activity history, not just min/max timestamps in a sample.
     * Missing publication times, unapproved patches and incomplete windows produce no completed stat row.
     */
    public static Dataset<Row> aggregate(Dataset<Row> reviews, Dataset<Row> patches,
                                         LocalDate coverageStart, LocalDate coverageEndExclusive,
                                         Instant aggregatedAt) {
        Objects.requireNonNull(aggregatedAt, "aggregatedAt");
        if (coverageStart == null || coverageEndExclusive == null || !coverageStart.isBefore(coverageEndExclusive)) {
            throw new IllegalArgumentException("Expected an explicit nonempty complete-history coverage interval");
        }
        requireType(patches, "gid", DataTypes.StringType);
        requireType(patches, "appid", DataTypes.LongType);
        requireType(patches, "published_ts", DataTypes.LongType);
        requireType(patches, "eligible_for_review_stats", DataTypes.BooleanType);
        Dataset<Row> approved = patches.filter(col("eligible_for_review_stats").equalTo(true)
                        .and(col("published_ts").isNotNull()))
                .select("gid", "appid", "published_ts").dropDuplicates();
        if (approved.filter(col("gid").isNull().or(length(trim(col("gid"))).equalTo(0))
                        .or(length(col("gid")).gt(20)).or(col("appid").isNull()).or(col("appid").leq(0))
                        .or(col("published_ts").lt(0))).limit(1).count() != 0
                || approved.groupBy("gid").count().filter(col("count").gt(1)).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid or conflicting patch identifiers / publication timestamps");
        }
        var kstStart = udf((UDF1<Long, Long>) timestamp ->
                TimeRule.startOfDay(PatchDateResolver.resolve(Instant.ofEpochSecond(timestamp))), DataTypes.LongType);
        Dataset<Row> windows = approved.withColumn("day_start", kstStart.apply(col("published_ts")))
                .withColumn("window_start", col("day_start").minus(SEVEN_DAYS_SECONDS))
                .withColumn("window_end", col("day_start").plus(SEVEN_DAYS_SECONDS))
                .filter(col("window_start").geq(TimeRule.startOfDay(coverageStart))
                        .and(col("window_end").leq(TimeRule.startOfDay(coverageEndExclusive)))
                        .and(col("window_end").leq(aggregatedAt.getEpochSecond())));

        Dataset<Row> input = DailyStatAggregator.selectInput(reviews);
        if (input.filter(DailyStatAggregator.invalidInput()).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid required review fields or timestamp order");
        }
        // Filter before the game/range join and before shuffling full revision history.
        input = input.filter(col("updated_ts").geq(TimeRule.startOfDay(coverageStart))
                        .and(col("updated_ts").lt(TimeRule.startOfDay(coverageEndExclusive)))
                        .and(col("collected_ts").leq(aggregatedAt.getEpochSecond())))
                .join(windows.select("appid").distinct(), new String[]{"appid"}, "left_semi");
        if (input.groupBy("recommendationid", "updated_ts", "collected_ts")
                .agg(countDistinct(struct(col("appid"), col("created_ts"), col("voted_up"))).alias("variants"))
                .filter(col("variants").gt(1)).limit(1).count() != 0) {
            throw new IllegalArgumentException("Conflicting review observations at identical timestamps");
        }
        Dataset<Row> revisionRows = input.alias("review");
        Dataset<Row> patchRows = windows.alias("patch");
        Column matchingWindow = col("review.appid").equalTo(col("patch.appid"))
                .and(col("review.updated_ts").geq(col("patch.window_start")))
                .and(col("review.updated_ts").lt(col("patch.window_end")));
        Dataset<Row> inWindows = revisionRows.join(patchRows, matchingWindow, "inner")
                .select(col("patch.gid").alias("gid"), col("review.recommendationid").alias("recommendationid"),
                        col("review.updated_ts").alias("updated_ts"), col("review.collected_ts").alias("collected_ts"),
                        col("review.voted_up").alias("voted_up"),
                        when(col("review.updated_ts").lt(col("patch.day_start")), "before").otherwise("after").alias("period"));
        Dataset<Row> latest = inWindows.withColumn("revision_rank", row_number().over(
                        Window.partitionBy("gid", "period", "recommendationid")
                                .orderBy(col("updated_ts").desc(), col("collected_ts").desc())))
                .filter(col("revision_rank").equalTo(1));
        Column before = col("period").equalTo("before");
        Column after = col("period").equalTo("after");
        Dataset<Row> counts = latest.groupBy("gid").agg(
                sum(when(before, 1L).otherwise(0L)).alias("before_review_count"),
                sum(when(before.and(col("voted_up")), 1L).otherwise(0L)).alias("before_positive_count"),
                sum(when(after, 1L).otherwise(0L)).alias("after_review_count"),
                sum(when(after.and(col("voted_up")), 1L).otherwise(0L)).alias("after_positive_count"));
        Dataset<Row> result = windows.join(counts, new String[]{"gid"}, "left")
                .na().fill(0L, new String[]{"before_review_count", "before_positive_count", "after_review_count", "after_positive_count"});
        if (result.filter(col("before_review_count").gt(Integer.MAX_VALUE)
                .or(col("after_review_count").gt(Integer.MAX_VALUE))).limit(1).count() != 0) {
            throw new IllegalArgumentException("Review count exceeds patch_stat INTEGER capacity");
        }
        Column beforePct = positivePercent("before");
        Column afterPct = positivePercent("after");
        return result.select(col("gid"), col("appid"), timestamp_seconds(col("published_ts")).alias("patched_at"),
                col("before_review_count").cast(DataTypes.IntegerType),
                round(beforePct, 2).cast(DataTypes.createDecimalType(5, 2)).alias("before_positive_pct"),
                col("after_review_count").cast(DataTypes.IntegerType),
                round(afterPct, 2).cast(DataTypes.createDecimalType(5, 2)).alias("after_positive_pct"),
                // Round only after subtraction; subtracting already rounded rates can add 0.01 pp error.
                round(afterPct.minus(beforePct), 2).cast(DataTypes.createDecimalType(5, 2)).alias("delta_pct"),
                lit(Date.valueOf(TimeRule.statDate(aggregatedAt.getEpochSecond()))).alias("stat_date"),
                lit(Timestamp.from(aggregatedAt)).alias("aggregated_at"));
    }

    private static Column positivePercent(String period) {
        return when(col(period + "_review_count").gt(0),
                col(period + "_positive_count").multiply(100.0).divide(col(period + "_review_count")))
                .otherwise(lit(null).cast(DataTypes.DoubleType));
    }

    private static void requireType(Dataset<Row> input, String column, org.apache.spark.sql.types.DataType expected) {
        if (!input.schema().apply(column).dataType().equals(expected)) {
            throw new IllegalArgumentException("Unexpected patch input type: " + column);
        }
    }
}
