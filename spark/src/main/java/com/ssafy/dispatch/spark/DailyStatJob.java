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
import static org.apache.spark.sql.functions.*;

/** Read-only delta preview. Does not write to HDFS or PostgreSQL. */
public final class DailyStatJob {
    private DailyStatJob() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: DailyStatJob <delta-date YYYY-MM-DD>");
        }
        String path = HdfsPaths.reviewDeltaOf(LocalDate.parse(args[0]).toString());
        try (SparkSession spark = SparkSessions.build("daily_stat_preview")) {
            Dataset<Row> raw = spark.read().parquet(path);
            raw.printSchema();
            raw.groupBy("appid", "language_code").count().orderBy("appid", "language_code").show(100, false);
            Dataset<Row> input = DailyStatAggregator.selectInput(raw).persist(StorageLevel.MEMORY_AND_DISK());
            try {
                System.out.println("Scope=delta-only preview; not full-history statistics. Input=" + path);
                System.out.println("input_count=" + input.count());
                System.out.println("invalid_count=" + input.filter(DailyStatAggregator.invalidInput()).count());
                input.agg(min("created_ts"), max("created_ts"), min("updated_ts"), max("updated_ts")).show(false);
                Dataset<Row> result = DailyStatAggregator.aggregate(input, Instant.now())
                        .persist(StorageLevel.MEMORY_AND_DISK());
                try {
                    result.orderBy("stat_date", "appid").show(100, false);
                    result.agg(sum("review_count"), sum("new_review_count"), sum("edited_review_count"),
                            sum("negative_count")).show(false);
                    System.out.println("daily_stat_rows=" + result.count());
                } finally {
                    result.unpersist();
                }
            } finally {
                input.unpersist();
            }
        }
    }
}
