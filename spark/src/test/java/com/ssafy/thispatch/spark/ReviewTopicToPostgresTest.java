package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ssafy.thispatch.common.SparkSessions;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DB 없이 검사할 수 있는 부분 — recommendationid → review_id 매핑, 창 밖·모르는 토픽 제외, 중복. */
class ReviewTopicToPostgresTest {
    private static SparkSession spark;
    /** AI 파케이 스키마 (ai/CONTRACT.md 3-3). */
    private static final StructType TOPIC = new StructType()
            .add("recommendationid", DataTypes.LongType).add("appid", DataTypes.LongType)
            .add("topic_id", DataTypes.ShortType).add("score", DataTypes.FloatType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("review_topic_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row t(long rec, int topic) { return RowFactory.create(rec, 730L, (short) topic, 0.9f); }
    private static Dataset<Row> topics(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), TOPIC).select("recommendationid", "appid", "topic_id");
    }
    private static Dataset<Row> recent(Object... pairs) {
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) rows.add(RowFactory.create((long) pairs[i], (long) pairs[i + 1]));
        return spark.createDataFrame(rows, ReviewTopicToPostgres.PAIR);
    }
    private static Dataset<Row> known(Long... ids) {
        return spark.createDataset(Arrays.asList(ids), Encoders.LONG()).toDF("known_topic");
    }

    @Test void mapsToReviewIdAndDropsOutOfWindowAndUnknownTopics() {
        Dataset<Row> pairs = topics(
                t(11, 1), t(11, 2),      // 창 안 리뷰, 토픽 둘 (다중 라벨)
                t(12, 5),                // 창 안
                t(99, 1),                // recent_review 에 없음 (창 밖) → 빠짐
                t(12, 9))                // topic 표에 없는 토픽 → 빠짐
                .select("recommendationid", "topic_id").distinct();
        List<Row> out = ReviewTopicToPostgres.assign(pairs, recent(11L, 1001L, 12L, 1002L), known(1L, 2L, 3L, 4L, 5L))
                .orderBy("review_id", "topic_id").collectAsList();
        assertEquals(3, out.size());
        assertEquals(1001L, (long) out.get(0).getAs("review_id")); assertEquals((short) 1, (short) out.get(0).getAs("topic_id"));
        assertEquals(1001L, (long) out.get(1).getAs("review_id")); assertEquals((short) 2, (short) out.get(1).getAs("topic_id"));
        assertEquals(1002L, (long) out.get(2).getAs("review_id")); assertEquals((short) 5, (short) out.get(2).getAs("topic_id"));
    }

    @Test void samePairAcrossDatePartitionsBecomesOneRow() {
        // 날짜 파티션 둘에서 같은 (리뷰, 토픽) 이 다시 나와도 PK (review_id, topic_id) 는 하나다
        Dataset<Row> pairs = topics(t(11, 1), t(11, 1), t(11, 1)).select("recommendationid", "topic_id").distinct();
        assertEquals(1, ReviewTopicToPostgres.assign(pairs, recent(11L, 1001L), known(1L)).count());
    }

    /** CONTRACT 3-3 — recent_review.language_code 가 검증된 8개일 때만 짝을 읽는다. */
    @Test void pairQueryKeepsOnlyEightVerifiedLanguages() {
        String sql = ReviewTopicToPostgres.pairsSql();
        assertEquals(8, ReviewTopicToPostgres.LANGUAGES.size());
        for (String l : List.of("english", "koreana", "schinese", "russian", "japanese", "german", "french", "spanish")) {
            assertTrue(sql.contains("'" + l + "'"), l);
        }
        assertTrue(sql.contains("WHERE language_code IN ("));
        assertFalse(sql.contains("turkish"));
    }

    @Test void invalidKeysAreCaught() {
        Dataset<Row> rows = topics(t(11, 1), RowFactory.create(null, 730L, (short) 1, 0.5f), t(0, 1), t(12, 0));
        assertEquals(3, rows.filter(ReviewTopicToPostgres.invalidInput()).count());
    }
}
