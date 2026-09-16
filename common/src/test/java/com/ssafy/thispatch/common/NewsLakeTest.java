package com.ssafy.thispatch.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.apache.spark.sql.functions.lit;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 날짜 파티션을 합쳐 읽을 때 같은 공지가 여러 번 세어지지 않는지 본다.
 *
 * <p>우리는 매일 게임마다 최신 100건을 받는다. 공지가 새로 안 올라온 게임은
 * 어제 것을 오늘 또 받는다. 그냥 다 읽으면 <b>같은 공지가 날짜 수만큼</b>
 * 세어지고 오류는 안 난다.
 */
class NewsLakeTest {

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() {
        spark = SparkSessions.builder("news-lake-test")
                .master("local[2]")
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

    private static Row news(String gid, long collectedTs, String title) {
        StructField[] fields = NewsSchema.NEWS_RAW.fields();
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = switch (fields[i].name()) {
                case "gid" -> gid;
                case "collected_ts" -> collectedTs;
                case "title" -> title;
                case "appid" -> 730L;
                case "is_patch" -> false;
                case "patch_reason" -> "0:unjudged";
                case "contents" -> "[p]본문[/p]";
                case "published_ts" -> 1_700_000_000L;
                case "feedname" -> "steam_community_announcements";
                default -> null;
            };
        }
        return RowFactory.create(values);
    }

    private Dataset<Row> lake(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), NewsSchema.NEWS_RAW);
    }

    private static List<String> titlesOf(Dataset<Row> df) {
        return df.collectAsList().stream()
                // ⚠ String.valueOf(getAs(...)) 를 쓰면 안 된다. 제네릭 추론이
                //   char[] 오버로드를 골라 ClassCastException 이 난다.
                .map(r -> r.getAs("title").toString())
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("같은 날 두 번 받은 것은 all() 에서 한 벌만 남는다")
    void allDropsSameDayDuplicates() {
        Dataset<Row> result = NewsLake.all(lake(
                news("1", 500L, "CS2 Update"),
                news("1", 500L, "CS2 Update")));

        assertEquals(1, result.count());
    }

    @Test
    @DisplayName("날짜가 다른 같은 공지는 all() 에 둘 다 남는다")
    void allKeepsEachDay() {
        Dataset<Row> result = NewsLake.all(lake(
                news("1", 500L, "CS2 Update"),
                news("1", 900L, "CS2 Update")));

        assertEquals(2, result.count());
    }

    @Test
    @DisplayName("latest() 는 공지 하나당 한 벌만 준다 — 이게 이 클래스가 있는 이유")
    void latestKeepsOnePerGid() {
        // 매일 최신 100건을 받으니 어제 것을 오늘 또 받는다.
        // 그냥 다 읽으면 한 공지가 며칠치만큼 세어진다.
        Dataset<Row> result = NewsLake.latest(lake(
                news("1", 500L, "CS2 Update"),
                news("1", 900L, "CS2 Update"),
                news("2", 700L, "다른 공지")));

        assertEquals(2, result.count());
        assertEquals(List.of("CS2 Update", "다른 공지"), titlesOf(result));
    }

    @Test
    @DisplayName("본문이 바뀌었으면 나중에 받은 쪽을 고른다")
    void latestPrefersTheNewestFetch() {
        Dataset<Row> result = NewsLake.latest(lake(
                news("1", 500L, "옛 제목"),
                news("1", 900L, "고친 제목")));

        assertEquals(1, result.count());
        assertEquals(List.of("고친 제목"), titlesOf(result));
    }

    @Test
    @DisplayName("latest() 가 칼럼을 하나도 잃지 않는다")
    void latestKeepsSchema() {
        Dataset<Row> result = NewsLake.latest(lake(news("1", 500L, "CS2 Update")));

        List<String> expected = new ArrayList<>(Arrays.asList(NewsSchema.NEWS_RAW.fieldNames()));
        List<String> actual = new ArrayList<>(Arrays.asList(result.columns()));
        expected.sort(null);
        actual.sort(null);

        assertEquals(expected, actual);
        assertTrue(actual.stream().noneMatch(c -> c.startsWith("_")), "내부 칼럼이 남았다: " + actual);
    }

    @Test
    void readingLegacyParquetDoesNotInventClassificationColumns(@TempDir Path directory) {
        String path = directory.resolve("news").toString();
        lake(news("1", 500L, "CS2 Update")).selectExpr(NewsSchema.NEWS_SOURCE.fieldNames())
                .write().parquet(path);
        Dataset<Row> result = NewsLake.read(spark, path);
        assertEquals(1, result.count());
        assertFalse(Arrays.asList(result.columns()).contains("is_patch"));
        assertFalse(Arrays.asList(result.columns()).contains("patch_reason"));
    }

    @Test
    void readingClassifiedParquetKeepsTheVerdict(@TempDir Path directory) {
        String path = directory.resolve("news").toString();
        lake(news("1", 500L, "CS2 Update")).withColumn("is_patch", lit(true))
                .withColumn("patch_reason", lit("PATCH_TITLE")).write().parquet(path);
        Row result = NewsLake.read(spark, path).first();
        assertTrue((boolean) result.getAs("is_patch"));
        assertEquals("PATCH_TITLE", result.getAs("patch_reason"));
    }

    @Test
    void partiallyConvertedPartitionsAreNotReportedAsClassified(@TempDir Path directory) {
        String path = directory.resolve("news").toString();
        Dataset<Row> notice = lake(news("1", 500L, "CS2 Update"));
        notice.selectExpr(NewsSchema.NEWS_SOURCE.fieldNames()).write().parquet(path + "/dt=2026-09-15");
        notice.withColumn("is_patch", lit(true)).withColumn("patch_reason", lit("PATCH_TITLE"))
                .write().parquet(path + "/dt=2026-09-16");
        assertThrows(IllegalArgumentException.class, () -> NewsLake.read(spark, path));
    }

    @Test
    @DisplayName("스키마 칼럼명이 DB news 테이블과 맞는다")
    void schemaMatchesTheDatabase() {
        // V1__init.sql 의 news 를 대조한 것이다. 이름이 어긋나면 적재에서 터진다.
        List<String> names = Arrays.asList(NewsSchema.NEWS_RAW.fieldNames());

        assertTrue(names.contains("gid"));
        assertTrue(names.contains("appid"));
        assertTrue(names.contains("title"));
        assertTrue(names.contains("contents"));
        assertTrue(names.contains("url"));
        assertTrue(names.contains("feed_tags"));
        assertTrue(names.contains("is_patch"));
        assertTrue(names.contains("patch_reason"));
        List<String> sourceFields = Arrays.asList(NewsSchema.NEWS_SOURCE.fieldNames());
        assertTrue(sourceFields.stream().noneMatch(n -> n.equals("is_patch") || n.equals("patch_reason")));
    }
}
