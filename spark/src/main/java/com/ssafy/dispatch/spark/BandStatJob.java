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

/** Read-only sample preview, not a complete game's production statistics. */
public final class BandStatJob {
    private BandStatJob() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: BandStatJob <delta-date YYYY-MM-DD>");
        }
        String path = HdfsPaths.reviewDeltaOf(LocalDate.parse(args[0]).toString());
        try (SparkSession spark = SparkSessions.build("band_stat_preview")) {
            System.out.println("Scope=delta-only preview; not full-game statistics. Input=" + path);
            Dataset<Row> raw = spark.read().parquet(path);
            raw.printSchema();
            Dataset<Row> latestReviews = BandStatAggregator.selectLatestReviews(raw)
                    .persist(StorageLevel.MEMORY_AND_DISK());
            try {
                Dataset<Row> quality = BandStatAggregator.qualityCounts(latestReviews);
                quality.orderBy("appid").show(100, false);
                quality.agg(sum("latest_review_count"), sum("missing_playtime_count"),
                        sum("negative_playtime_count"), sum("included_review_count")).show(false);
                Dataset<Row> bands = BandStatAggregator.aggregateLatestReviews(latestReviews, Instant.now())
                        .persist(StorageLevel.MEMORY_AND_DISK());
                try {
                    bands.orderBy("appid", "band_no").show(100, false);
                    bands.agg(sum("review_count"), sum("positive_count")).show(false);
                } finally {
                    bands.unpersist();
                }
            } finally {
                latestReviews.unpersist();
            }
        }
    }
}
