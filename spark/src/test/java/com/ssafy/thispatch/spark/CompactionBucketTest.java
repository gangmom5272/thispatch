package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ssafy.thispatch.common.ReviewLake;
import com.ssafy.thispatch.common.SparkSessions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 조각으로 나눠 중복 제거해도 전체를 한 번에 한 것과 같은지 본다.
 *
 * <p>compaction 이 조각(bucket) 단위로 확정하는 구조(2026-09-22)의 전제가 여기다 —
 * 같은 recommendationid 가 조각 두 곳에 갈리면 중복이 살아남는다.
 */
class CompactionBucketTest {

    private static SparkSession spark;

    private static final StructType SCHEMA = new StructType()
            .add("recommendationid", DataTypes.LongType, false)
            .add("updated_ts", DataTypes.LongType, false)
            .add("collected_ts", DataTypes.LongType, false)
            .add("review_text", DataTypes.StringType, true);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("compaction_bucket_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "3")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    /** 리뷰 60개. 절반은 완전히 같은 레코드가 두 벌(수집이 겹친 것), 셋은 판본이 둘(고쳐진 리뷰). */
    private static Dataset<Row> lake() {
        List<Row> rows = new ArrayList<>();
        for (long id = 1; id <= 60; id++) {
            rows.add(RowFactory.create(id, 100L, 1_000L + id, "리뷰 " + id));
            if (id % 2 == 0) {
                rows.add(RowFactory.create(id, 100L, 2_000L + id, "리뷰 " + id));   // 같은 (id, updated_ts) — 중복
            }
            if (id % 20 == 0) {
                rows.add(RowFactory.create(id, 200L, 3_000L + id, "고친 리뷰 " + id));  // 새 판본 — 남아야 한다
            }
        }
        return spark.createDataFrame(rows, SCHEMA);
    }

    private static Set<String> keys(Dataset<Row> ds) {
        Set<String> out = new HashSet<>();
        for (Row r : ds.collectAsList()) {
            out.add(r.getLong(0) + ":" + r.getLong(1));
        }
        return out;
    }

    @Test
    @DisplayName("조각별 중복 제거를 합치면 전체 중복 제거와 같다 — 조각 수가 몇이든")
    void bucketedDedupEqualsWholeDedup() {
        Dataset<Row> lake = lake();
        Set<String> whole = keys(ReviewLake.all(lake));
        assertEquals(63, whole.size(), "60개 리뷰 + 판본 둘인 것 3개");

        for (int buckets : Arrays.asList(1, 2, 4, 7)) {
            Set<String> merged = new HashSet<>();
            long total = 0;
            for (int b = 0; b < buckets; b++) {
                Set<String> part = keys(ReviewLake.all(Compaction.bucketOf(lake, buckets, b)));
                total += part.size();
                merged.addAll(part);
            }
            assertEquals(whole, merged, "조각 " + buckets + "개: 합친 결과가 다르다");
            assertEquals(whole.size(), total, "조각 " + buckets + "개: 같은 레코드가 두 조각에 들어갔다");
        }
    }

    @Test
    @DisplayName("같은 리뷰는 늘 같은 조각에 떨어진다")
    void sameReviewAlwaysLandsInTheSameBucket() {
        Dataset<Row> lake = lake();
        Map<Long, Integer> where = new HashMap<>();
        for (int b = 0; b < 4; b++) {
            for (Row r : Compaction.bucketOf(lake, 4, b).collectAsList()) {
                Integer before = where.put(r.getLong(0), b);
                if (before != null) {
                    assertEquals(before.intValue(), b, "리뷰 " + r.getLong(0) + " 가 조각 두 곳에 있다");
                }
            }
        }
        assertEquals(60, where.size(), "리뷰가 빠졌다");
    }
}
