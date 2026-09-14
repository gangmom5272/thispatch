package com.ssafy.dispatch.spark;

import com.ssafy.dispatch.common.ReviewSchema;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

import static org.apache.spark.sql.functions.*;

/** Counts each review once, using its latest observed language and recommendation. */
public final class LanguageStatAggregator {
    private static final String[] INPUT_COLUMNS = {
        "appid", "recommendationid", "created_ts", "updated_ts", "collected_ts",
        "voted_up", "language_code"
    };

    private LanguageStatAggregator() {}

    public static Dataset<Row> selectInput(Dataset<Row> reviews) {
        for (String name : INPUT_COLUMNS) {
            if (!reviews.schema().apply(name).dataType().equals(ReviewSchema.REVIEW_RAW.apply(name).dataType())) {
                throw new IllegalArgumentException("Unexpected input type: " + name);
            }
        }
        Column[] columns = Arrays.stream(INPUT_COLUMNS).map(org.apache.spark.sql.functions::col)
                .toArray(Column[]::new);
        return reviews.select(columns);
    }

    public static Dataset<Row> aggregate(Dataset<Row> reviews, Instant aggregatedAt) {
        Dataset<Row> input = selectInput(reviews);
        if (input.filter(DailyStatAggregator.invalidInput()).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid required fields or timestamp order");
        }
        // Preserve the collector's codes. Silently dropping/mapping them would change totals or FK meaning.
        // Actual membership in the DB language table must be checked by the loading stage.
        Column invalidLanguage = col("language_code").isNull()
                .or(length(trim(col("language_code"))).equalTo(0))
                .or(length(col("language_code")).gt(20))
                .or(col("language_code").notEqual(trim(col("language_code"))));
        if (input.filter(invalidLanguage).limit(1).count() != 0) {
            throw new IllegalArgumentException("language_code must be nonempty, unpadded and at most 20 characters");
        }
        Dataset<Row> conflicts = input.groupBy("appid", "recommendationid", "updated_ts", "collected_ts")
                .agg(countDistinct(struct(col("created_ts"), col("voted_up"), col("language_code")))
                        .alias("variants"))
                .filter(col("variants").gt(1));
        if (conflicts.limit(1).count() != 0) {
            throw new IllegalArgumentException("Conflicting observations at identical timestamps");
        }
        // Do not partition by language: a language change must replace, not duplicate, the old record.
        Dataset<Row> latestReviews = input.withColumn("latest_rank", row_number().over(
                        Window.partitionBy("appid", "recommendationid")
                                .orderBy(col("updated_ts").desc(), col("collected_ts").desc())))
                .filter(col("latest_rank").equalTo(1)).drop("latest_rank");
        return latestReviews.groupBy("appid", "language_code")
                .agg(count(lit(1)).alias("review_count"),
                        sum(when(col("voted_up"), 1L).otherwise(0L)).alias("positive_count"))
                .withColumn("aggregated_at", lit(Timestamp.from(aggregatedAt)));
    }
}
