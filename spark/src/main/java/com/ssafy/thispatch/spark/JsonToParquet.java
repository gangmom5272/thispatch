package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.ReviewSchema;
import com.ssafy.thispatch.common.SparkSessions;
import com.ssafy.thispatch.common.TimeRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * 수집기가 떨군 {@code .jsonl.gz} 원본을 Parquet 으로 바꾼다.
 *
 * <pre>
 *   /review_landing/dt=2026-09-11/*.jsonl.gz   ->   /review_raw/delta/dt=2026-09-11/
 * </pre>
 *
 * <p><b>compaction 과는 다른 잡이다.</b> 이건 매일 돌면서 그날 받은 것을 Parquet 으로
 * 바꾸고, compaction 은 주 1회 base 와 delta 를 합쳐 파일 개수를 줄인다.
 *
 * <p>실행
 *
 * <pre>
 *   spark-submit --class com.ssafy.thispatch.spark.JsonToParquet \
 *                dispatch-spark.jar [yyyy-MM-dd]
 * </pre>
 *
 * <p>날짜를 안 주면 오늘(KST)로 본다.
 */
public final class JsonToParquet {

    private JsonToParquet() {
    }

    /**
     * 원본을 읽을 때 쓰는 스키마.
     *
     * <p>타입 추론에 맡기지 않는다. {@code weighted_vote_score} 가 응답마다
     * 문자열이기도 하고 실수이기도 해서(샘플 8,034건 중 문자열 899건 · 실수 3,096건),
     * 추론에 맡기면 파일마다 다른 타입이 나온다.
     *
     * <p>흔들리는 것은 <b>일단 문자열로 받아서</b> 명시적으로 캐스팅한다.
     * {@code recommendationid} 도 스팀이 숫자 문자열로 준다.
     */
    private static final StructType LANDING = new StructType()
            .add("recommendationid", DataTypes.StringType, true)
            .add("appid", DataTypes.LongType, true)
            .add("language", DataTypes.StringType, true)
            .add("review", DataTypes.StringType, true)
            .add("timestamp_created", DataTypes.LongType, true)
            .add("timestamp_updated", DataTypes.LongType, true)
            .add("voted_up", DataTypes.BooleanType, true)
            .add("votes_up", DataTypes.IntegerType, true)
            .add("votes_funny", DataTypes.IntegerType, true)
            .add("weighted_vote_score", DataTypes.StringType, true)
            .add("comment_count", DataTypes.IntegerType, true)
            .add("steam_purchase", DataTypes.BooleanType, true)
            .add("received_for_free", DataTypes.BooleanType, true)
            .add("refunded", DataTypes.BooleanType, true)
            .add("written_during_early_access", DataTypes.BooleanType, true)
            .add("primarily_steam_deck", DataTypes.BooleanType, true)
            .add("collected_ts", DataTypes.LongType, true)
            .add("author", new StructType()
                    .add("steamid", DataTypes.StringType, true)
                    .add("num_games_owned", DataTypes.IntegerType, true)
                    .add("num_reviews", DataTypes.IntegerType, true)
                    .add("playtime_forever", DataTypes.IntegerType, true)
                    .add("playtime_last_two_weeks", DataTypes.IntegerType, true)
                    .add("playtime_at_review", DataTypes.IntegerType, true)
                    .add("last_played", DataTypes.LongType, true), true);

    /** 캐스팅해서 null 이 될 수 있는 칼럼. 몇 개 나왔는지 세서 로그에 남긴다. */
    private static final String[] WATCH = {
            "recommendationid", "weighted_vote_score", "created_ts", "updated_ts",
            "appid", "voted_up", "playtime_at_review",
    };

    public static void main(String[] args) {
        String dt = (args.length > 0 && !args[0].isBlank())
                ? args[0]
                : TimeRule.partition(TimeRule.today());

        String src = HdfsPaths.reviewLandingOf(dt) + "/*.jsonl.gz";
        String dst = HdfsPaths.reviewDeltaOf(dt);

        SparkSession spark = SparkSessions.build("json-to-parquet-" + dt);
        try {
            System.out.println("읽는다  " + src);
            Dataset<Row> raw = spark.read().schema(LANDING).json(src);
            long inCount = raw.count();
            System.out.println("원본    " + inCount + "건");

            if (inCount == 0) {
                System.out.println("받은 것이 없다. 아무것도 쓰지 않는다.");
                return;
            }

            Dataset<Row> mapped = toOurShape(raw);

            // ANSI 를 껐으므로 타입이 안 맞으면 예외 대신 null 이 된다.
            // 세지 않으면 조용히 오염된다.
            reportNulls(mapped, inCount);

            // 같은 (리뷰, 수정시각) 이 여러 번 수집된 것만 하나로 줄인다.
            // 버전은 지우지 않는다 — 같은 리뷰의 다른 updated_ts 는 각각 남는다.
            Dataset<Row> deduped = mapped.dropDuplicates(ReviewSchema.DEDUP_KEY);
            long outCount = deduped.count();
            System.out.println("중복 제거 후 " + outCount + "건  (" + (inCount - outCount) + "건 줄었다)");

            // 파일 개수를 줄인다. 수집 1회에 파일이 1,974개 나오는데(실측)
            // 그대로 두면 데이터 크기와 무관하게 Spark 가 파일 여는 비용만으로 느려진다.
            //
            // 무선 링크가 3.2 MB/s 라 셔플이 비싸므로 repartition 이 아니라
            // coalesce 를 쓴다. coalesce 는 셔플 없이 합친다.
            int files = Math.max(1, (int) Math.min(16, outCount / 200_000 + 1));

            deduped.coalesce(files)
                    .write()
                    .mode(SaveMode.Overwrite)
                    .parquet(dst);

            System.out.println("썼다    " + dst + "  (파일 " + files + "개)");
        } finally {
            spark.stop();
        }
    }

    /**
     * 스팀 필드명을 우리 이름으로 바꾸고 타입을 맞춘다.
     *
     * <p>칼럼명은 DB({@code recent_review})를 따른다. 값은 받은 그대로 두고
     * {@code TIMESTAMPTZ} 변환은 PostgreSQL 적재 단계에서 한다.
     */
    static Dataset<Row> toOurShape(Dataset<Row> raw) {
        return raw.select(
                col("recommendationid").cast(DataTypes.LongType).as("recommendationid"),
                col("appid").cast(DataTypes.LongType).as("appid"),
                col("author.steamid").as("steam_id"),

                col("review").as("review_text"),
                col("language").as("language_code"),

                col("timestamp_created").as("created_ts"),
                col("timestamp_updated").as("updated_ts"),

                col("voted_up"),
                col("votes_up"),
                col("votes_funny"),
                col("weighted_vote_score").cast(DataTypes.DoubleType).as("weighted_vote_score"),
                col("comment_count"),

                col("steam_purchase"),
                col("received_for_free"),
                col("refunded"),
                col("written_during_early_access"),
                col("primarily_steam_deck"),

                col("author.playtime_at_review").as("playtime_at_review"),
                col("author.playtime_forever").as("playtime_forever"),
                col("author.playtime_last_two_weeks").as("playtime_last_two_weeks"),
                col("author.num_games_owned").as("num_games_owned"),
                col("author.num_reviews").as("num_reviews"),
                col("author.last_played").as("last_played"),

                col("collected_ts"));
    }

    /**
     * 캐스팅 뒤 null 이 몇 개 생겼는지 센다.
     *
     * <p>{@code playtime_at_review} 는 스팀이 원래 안 주는 경우가 있어 null 이
     * 정상이다. 나머지가 null 이면 캐스팅이 실패한 것이니 눈에 띄어야 한다.
     */
    static void reportNulls(Dataset<Row> df, long total) {
        List<Column> aggs = new ArrayList<>();
        for (String c : WATCH) {
            aggs.add(sum(when(col(c).isNull(), lit(1L)).otherwise(lit(0L))).as(c));
        }
        Row r = df.agg(aggs.get(0), aggs.subList(1, aggs.size()).toArray(new Column[0])).first();

        System.out.println("--- 캐스팅 결과 null 개수 (전체 " + total + "건) ---");
        for (String c : WATCH) {
            long n = r.isNullAt(r.fieldIndex(c)) ? 0L : r.getLong(r.fieldIndex(c));
            if (n == 0) {
                continue;
            }
            String note = "playtime_at_review".equals(c) ? "  (스팀이 안 주는 경우가 있어 정상)" : "  <- 확인 필요";
            System.out.printf("  %-22s %6d건  %5.1f%%%s%n", c, n, 100.0 * n / total, note);
        }
        System.out.println("--------------------------------------------");
    }
}
