package com.ssafy.thispatch.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.types.StructField;

/**
 * base + delta 를 합쳐 읽을 때 중복이 제대로 정리되는지 본다.
 *
 * <p>여기가 틀리면 화면 숫자가 조용히 틀린다. 오류가 안 나기 때문에
 * 테스트로만 잡을 수 있다.
 */
class ReviewLakeTest {

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() {
        spark = SparkSessions.builder("review-lake-test")
                .master("local[2]")
                // 테스트에서 60 은 과분할이다. 빈 태스크 58개를 만드느라 더 느려진다.
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.ui.enabled", "false")
                .getOrCreate();
    }

    @AfterAll
    static void stopSpark() {
        if (spark != null) {
            spark.stop();
        }
    }

    /**
     * 리뷰 한 건을 만든다. 나머지 칼럼은 스키마 기본값으로 채운다 —
     * 이 테스트가 보는 것은 {@code recommendationid · updated_ts · collected_ts}
     * 세 개뿐이다.
     */
    private static Row review(long id, long updatedTs, long collectedTs, String text) {
        StructField[] fields = ReviewSchema.REVIEW_RAW.fields();
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = switch (fields[i].name()) {
                case "recommendationid" -> id;
                case "updated_ts" -> updatedTs;
                case "collected_ts" -> collectedTs;
                case "review_text" -> text;
                case "appid" -> 730L;
                case "created_ts" -> 1_700_000_000L;
                case "language_code" -> "koreana";
                case "voted_up" -> true;
                default -> null;
            };
        }
        return RowFactory.create(values);
    }

    private Dataset<Row> lake(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), ReviewSchema.REVIEW_RAW);
    }

    private static List<String> textsOf(Dataset<Row> df) {
        return df.collectAsList().stream()
                .map(r -> r.getAs("review_text").toString())
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("재수집으로 완전히 같은 레코드가 둘 들어와도 all() 은 한 벌만 준다")
    void allDropsExactDuplicates() {
        // 조각이 실패해 재개되면 받다 만 게임의 페이지가 겹쳐 들어온다.
        // 같은 리뷰 · 같은 updated_ts 라 내용이 완전히 같다.
        Dataset<Row> result = ReviewLake.all(lake(
                review(1L, 100L, 500L, "재밌음"),
                review(1L, 100L, 900L, "재밌음")));

        assertEquals(1, result.count());
    }

    @Test
    @DisplayName("사람이 고친 것은 all() 에서 판이 둘 다 남는다")
    void allKeepsEveryVersion() {
        // daily_stat.edited_review_count 가 「그날 고쳐진 리뷰」를 세므로
        // 옛 판을 지우면 지난 날짜를 다시 만들 수 없다.
        Dataset<Row> result = ReviewLake.all(lake(
                review(1L, 100L, 500L, "재밌음"),
                review(1L, 200L, 900L, "환불했습니다")));

        assertEquals(2, result.count());
        assertEquals(List.of("재밌음", "환불했습니다"), textsOf(result));
    }

    @Test
    @DisplayName("latest() 는 리뷰당 updated_ts 가 가장 큰 한 벌만 준다")
    void latestKeepsNewestVersion() {
        Dataset<Row> result = ReviewLake.latest(lake(
                review(1L, 100L, 500L, "재밌음"),
                review(1L, 200L, 900L, "환불했습니다"),
                review(2L, 150L, 600L, "다른 리뷰")));

        assertEquals(2, result.count());
        assertEquals(List.of("다른 리뷰", "환불했습니다"), textsOf(result));
    }

    @Test
    @DisplayName("base 의 옛 판과 delta 의 새 판이 만나도 리뷰는 한 건으로 센다")
    void latestMergesBaseAndDelta() {
        // 이게 이 클래스가 있는 이유다. 그냥 union 하면 한 리뷰가 두 번 세어지고
        // 오류는 안 난다.
        Dataset<Row> base = lake(review(1L, 100L, 500L, "재밌음"));
        Dataset<Row> delta = lake(review(1L, 200L, 900L, "환불했습니다"));

        Dataset<Row> result = ReviewLake.latest(base.union(delta));

        assertEquals(1, result.count());
        assertEquals(List.of("환불했습니다"), textsOf(result));
    }

    @Test
    @DisplayName("updated_ts 가 같으면 나중에 수집한 쪽을 고른다")
    void latestBreaksTieByCollectedTs() {
        Dataset<Row> result = ReviewLake.latest(lake(
                review(1L, 100L, 500L, "먼저 받은 것"),
                review(1L, 100L, 900L, "나중에 받은 것")));

        assertEquals(1, result.count());
        assertEquals(List.of("나중에 받은 것"), textsOf(result));
    }

    @Test
    @DisplayName("latest() 가 칼럼을 하나도 잃지 않는다")
    void latestKeepsSchema() {
        Dataset<Row> result = ReviewLake.latest(lake(review(1L, 100L, 500L, "재밌음")));

        List<String> expected = new ArrayList<>(Arrays.asList(ReviewSchema.REVIEW_RAW.fieldNames()));
        List<String> actual = new ArrayList<>(Arrays.asList(result.columns()));
        expected.sort(null);
        actual.sort(null);

        assertEquals(expected, actual);
        // 비교용으로 만든 _order · _tie 가 새어 나오면 안 된다.
        assertTrue(actual.stream().noneMatch(c -> c.startsWith("_")), "내부 칼럼이 남았다: " + actual);
    }
}
