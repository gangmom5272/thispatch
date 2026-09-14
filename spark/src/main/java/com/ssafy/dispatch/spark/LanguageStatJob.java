package com.ssafy.dispatch.spark;

import com.ssafy.dispatch.common.HdfsPaths;
import com.ssafy.dispatch.common.SparkSessions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;

import static org.apache.spark.sql.functions.sum;

/** Read-only delta preview; no database loading or LLM summarization. */
public final class LanguageStatJob {
    private LanguageStatJob() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: LanguageStatJob <delta-date YYYY-MM-DD>");
        }
        String path = HdfsPaths.reviewDeltaOf(LocalDate.parse(args[0]).toString());
        try (SparkSession spark = SparkSessions.build("language_stat_preview")) {
            System.out.println("Scope=delta-only preview; not full-game statistics. Input=" + path);
            Dataset<Row> raw = spark.read().parquet(path);
            raw.printSchema();
            Dataset<Row> input = LanguageStatAggregator.selectInput(raw)
                    .persist(StorageLevel.MEMORY_AND_DISK());
            try {
                System.out.println("input_count=" + input.count());
                Dataset<Row> result = LanguageStatAggregator.aggregate(input, Instant.now())
                        .persist(StorageLevel.MEMORY_AND_DISK());
                try {
                    result.orderBy("appid", "language_code").show(100, false);
                    result.groupBy("appid").agg(sum("review_count").alias("review_count"),
                            sum("positive_count").alias("positive_count")).orderBy("appid").show(100, false);
                    result.agg(sum("review_count"), sum("positive_count")).show(false);
                    System.out.println("language_stat_rows=" + result.count());
                } finally {
                    result.unpersist();
                }
            } finally {
                input.unpersist();
            }
        }
    }
}
