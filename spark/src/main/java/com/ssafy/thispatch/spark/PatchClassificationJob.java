package com.ssafy.thispatch.spark;

import java.io.IOException;
import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/** Reads news_raw Parquet and previews classification. Does not write to HDFS or a database. */
public final class PatchClassificationJob {
    private PatchClassificationJob() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: PatchClassificationJob [news_raw Parquet path]");
        }
        String inputPath = args.length == 0 ? HdfsPaths.NEWS_RAW : args[0];
        try (SparkSession spark = SparkSessions.build("patch_classification_preview")) {
            Dataset<Row> input = spark.read().parquet(inputPath).persist(StorageLevel.MEMORY_AND_DISK());
            try {
                System.out.println("Input=" + inputPath);
                System.out.println("input_rows=" + input.count());
                Dataset<Row> result = PatchClassificationProcessor.classify(input)
                        .persist(StorageLevel.MEMORY_AND_DISK());
                try {
                    System.out.println("classified_rows=" + result.count());
                    result.groupBy("patch_decision", "patch_scope").count()
                            .orderBy("patch_decision", "patch_scope").show(100, false);
                    result.select("appid", "gid", "title", "published_ts", "is_patch", "patch_decision", "patch_scope", "patch_reason")
                            .orderBy("appid", "gid").show(20, 120, false);
                    System.out.println("rule_version=" + PatchClassifier.RULE_VERSION);
                } finally {
                    result.unpersist();
                }
            } finally {
                input.unpersist();
            }
        }
    }
}
