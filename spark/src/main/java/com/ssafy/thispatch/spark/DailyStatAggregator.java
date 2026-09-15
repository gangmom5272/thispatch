package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.ReviewSchema;
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
import java.util.Arrays;
import static org.apache.spark.sql.functions.*;

/** Aggregates observed activity; never invents the vote of an unseen original review. */
public final class DailyStatAggregator {
    private static final String[] INPUT_COLUMNS = {
        "appid", "recommendationid", "created_ts", "updated_ts", "collected_ts", "voted_up"
    };

    private DailyStatAggregator() {}

    public static Dataset<Row> selectInput(Dataset<Row> reviews) {
        // Fail rather than silently cast IDs and timestamps with ANSI disabled.
        for (String name : INPUT_COLUMNS) {
            if (!reviews.schema().apply(name).dataType().equals(ReviewSchema.REVIEW_RAW.apply(name).dataType())) {
                throw new IllegalArgumentException("Unexpected input type: " + name);
            }
        }
        return reviews.select(Arrays.stream(INPUT_COLUMNS)
                .map(org.apache.spark.sql.functions::col).toArray(Column[]::new));
    }

    public static Column invalidInput() {
        Column invalid = lit(false);
        for (String name : INPUT_COLUMNS) {
            invalid = invalid.or(col(name).isNull());
        }
        return invalid.or(col("appid").leq(0)).or(col("recommendationid").leq(0))
                .or(col("created_ts").lt(0)).or(col("updated_ts").lt(col("created_ts")))
                .or(col("collected_ts").lt(col("updated_ts")));
    }

    public static Dataset<Row> aggregate(Dataset<Row> reviews, Instant aggregatedAt) {
        Dataset<Row> input = selectInput(reviews);
        if (input.filter(invalidInput()).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid required fields or timestamp order");
        }
        // Equal timestamps with different content cannot be resolved by arbitrary row order.
        Dataset<Row> conflicts = input.groupBy("recommendationid", "updated_ts", "collected_ts")
                .agg(countDistinct(struct(col("appid"), col("created_ts"), col("voted_up"))).alias("variants"))
                .filter(col("variants").gt(1));
        if (conflicts.limit(1).count() != 0) {
            throw new IllegalArgumentException("Conflicting observations at identical timestamps");
        }
        Column[] versionKey = Arrays.stream(ReviewSchema.DEDUP_KEY)
                .map(org.apache.spark.sql.functions::col).toArray(Column[]::new);
        Dataset<Row> versions = input.withColumn("version_rank", row_number().over(
                        Window.partitionBy(versionKey).orderBy(col("collected_ts").desc())))
                .filter(col("version_rank").equalTo(1)).drop("version_rank");
        var kstDate = udf((UDF1<Long, Date>) seconds -> Date.valueOf(TimeRule.statDate(seconds)), DataTypes.DateType);
        Dataset<Row> dailyReviews = versions
                .withColumn("stat_date", kstDate.apply(col("updated_ts")))
                .withColumn("created_date", kstDate.apply(col("created_ts")))
                .withColumn("daily_rank", row_number().over(
                        Window.partitionBy("appid", "recommendationid", "stat_date")
                                .orderBy(col("updated_ts").desc(), col("collected_ts").desc())))
                .filter(col("daily_rank").equalTo(1));
        Column isNew = col("created_date").equalTo(col("stat_date"));
        Column positive = col("voted_up");
        return dailyReviews.groupBy("appid", "stat_date")
                .agg(count(lit(1)).alias("review_count"),
                        sum(when(positive.equalTo(false), 1L).otherwise(0L)).alias("negative_count"),
                        sum(when(isNew, 1L).otherwise(0L)).alias("new_review_count"),
                        sum(when(isNew.and(positive), 1L).otherwise(0L)).alias("new_positive_count"),
                        sum(when(isNew.equalTo(false), 1L).otherwise(0L)).alias("edited_review_count"),
                        sum(when(isNew.equalTo(false).and(positive), 1L).otherwise(0L)).alias("edited_positive_count"))
                .withColumn("aggregated_at", lit(Timestamp.from(aggregatedAt)));
    }
}
