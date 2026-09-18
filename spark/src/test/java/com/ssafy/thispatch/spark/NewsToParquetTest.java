package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ssafy.thispatch.common.NewsSchema;
import com.ssafy.thispatch.common.SparkSessions;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 개발사 공지만 남기는 기준(성현님 결정 · 2026-09-18)이 충돌 검사보다 먼저 일어나는지.
 * 실측 그대로: 개발사 공지는 is_external_url=true 로 오고, RSS 와 스팀 스토어 공지가 여러 appid 에 같은 gid 로 붙는다.
 */
class NewsToParquetTest {
    private static SparkSession spark;
    /** landing JSON 스키마 그대로 (NewsToParquet.LANDING 과 같은 모양). */
    private static final StructType LANDING = new StructType()
            .add("gid", DataTypes.StringType).add("appid", DataTypes.LongType)
            .add("title", DataTypes.StringType).add("contents", DataTypes.StringType)
            .add("url", DataTypes.StringType).add("author", DataTypes.StringType)
            .add("feedname", DataTypes.StringType).add("feedlabel", DataTypes.StringType)
            .add("feed_type", DataTypes.IntegerType).add("date", DataTypes.LongType)
            .add("tags", DataTypes.createArrayType(DataTypes.StringType))
            .add("is_external_url", DataTypes.BooleanType).add("collected_ts", DataTypes.LongType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("news_to_parquet_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row landing(String gid, long appid, String feedname, Integer feedType, Boolean external, long collected) {
        return RowFactory.create(gid, appid, "Update 1.2 — Fixed a crash.", "Fixed a crash.",
                "https://steamstore-a.akamaihd.net/news/externalpost/" + feedname + "/" + gid,
                "Dev", feedname, feedname, feedType, 100L, List.of("patchnotes"), external, collected);
    }
    /** 개발사 공지 — 실측대로 is_external_url=true 로 온다. */
    private static Row developer(String gid, long appid, long collected) {
        return landing(gid, appid, NewsToParquet.DEVELOPER_FEED, 1, true, collected);
    }
    private static Dataset<Row> input(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), LANDING); }

    @Test void keepsDeveloperNoticesEvenThoughSteamFlagsThemExternal() {
        List<Row> out = NewsToParquet.toOurShape(input(
                developer("1", 730, 200),
                developer("2", 570, 200))).collectAsList();
        assertEquals(2, out.size(), "개발사 공지는 is_external_url=true 여도 전부 남는다");
        assertEquals(Arrays.asList(NewsSchema.NEWS_RAW.fieldNames()),
                Arrays.asList(out.get(0).schema().fieldNames()));
        assertTrue(out.stream().allMatch(r -> Boolean.TRUE.equals(r.getAs("is_external_url"))),
                "is_external_url 칼럼은 값 그대로 보존한다");
    }

    @Test void rssAndStoreNoticesSharedByManyGamesAreDroppedBeforeTheConflictCheck() {
        Dataset<Row> raw = input(
                developer("1", 730, 200),
                // 뉴스 사이트 RSS 1건이 두 게임에 붙음 (is_external_url=true)
                landing("9", 730, "pcgamer", 0, true, 200),
                landing("9", 570, "pcgamer", 0, true, 200),
                // 스팀 스토어 세일 공지 1건이 두 게임에 붙음 (is_external_url=false)
                landing("8", 730, "steam_announce", 0, false, 200),
                landing("8", 570, "steam_announce", 0, false, 200),
                // feedname 이 없는 행 — 개발사 공지라고 볼 근거가 없다
                landing("7", 730, null, 0, false, 200));
        assertEquals(5, raw.filter(NewsToParquet.excluded()).count());

        List<Row> out = NewsToParquet.toOurShape(raw).collectAsList();
        assertEquals(1, out.size(), "RSS · 스토어 공지 · feedname 없음은 빠지고 개발사 공지만 남는다");
        assertEquals("1", out.get(0).getAs("gid"));
    }

    @Test void conflictAmongDeveloperNoticesStillStopsTheConversion() {
        // 개발사 공지인데 같은 gid·collected_ts 에 다른 appid 면 예전처럼 멈춘다 — 실측엔 없지만 생기면 사람이 봐야 한다.
        Dataset<Row> raw = input(
                developer("1", 730, 200),
                developer("1", 570, 200));
        assertThrows(IllegalArgumentException.class, () -> NewsToParquet.toOurShape(raw).count());
    }
}
