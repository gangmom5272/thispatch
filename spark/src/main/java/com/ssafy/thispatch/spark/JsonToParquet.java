package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.ReviewSchema;
import com.ssafy.thispatch.common.SparkSessions;
import com.ssafy.thispatch.common.TimeRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
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
 *   spark-submit --class com.ssafy.thispatch.spark.JsonToParquet thispatch-spark.jar
 *       아직 delta 에 없는 landing 날짜를 전부 (오늘 것은 빼고)
 *
 *   ... thispatch-spark.jar 2026-09-14
 *       그 날짜만. 오늘 것을 지금 바꾸고 싶을 때도 이렇게 준다
 *
 *   ... thispatch-spark.jar --all
 *       landing 에 있는 모든 날짜를 처음부터 다시
 * </pre>
 *
 * <p><b>날짜를 안 주는 것이 기본이다.</b> 예전에는 「오늘」을 기본으로 삼았는데,
 * 수집이 하루 안에 끝난다는 전제였다. 최초 전량 수집은 사흘 넘게 걸려서 landing 이
 * 날짜별로 갈라진다. 사람이 그 날짜들을 기억했다가 여러 번 돌려야 했고, 하나를
 * 빠뜨리면 그날 것이 통째로 빠진 채 오류도 안 났다. {@link #pending}
 * 을 보라.
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
        String arg = (args.length > 0 && !args[0].isBlank()) ? args[0].trim() : "";

        SparkSession spark = SparkSessions.build("json-to-parquet");
        try {
            List<String> dates;
            if (arg.isEmpty()) {
                dates = pending(spark);
                if (dates.isEmpty()) {
                    System.out.println("변환할 것이 없다. landing 의 날짜가 전부 delta 에 있다.");
                    return;
                }
                System.out.println("변환 안 된 날짜 " + dates.size() + "개: " + dates);
            } else if ("--all".equals(arg)) {
                dates = listPartitions(spark, HdfsPaths.REVIEW_LANDING);
                System.out.println("landing 의 모든 날짜 " + dates.size() + "개를 다시 만든다: " + dates);
            } else {
                dates = List.of(arg);
            }

            for (String dt : dates) {
                convertOne(spark, dt);
            }
        } finally {
            spark.stop();
        }
    }

    /**
     * 아직 delta 로 바뀌지 않은 landing 날짜.
     *
     * <p><b>왜 필요한가.</b> 예전에는 날짜 하나만 받아서 그날치만 바꿨다. 수집이
     * 하루 안에 끝난다는 전제였다. 실제로는 최초 전량 수집이 사흘 넘게 돌아서
     * landing 이 이렇게 갈라진다(2026-09-15 실측).
     *
     * <pre>
     *   /review_landing/dt=2026-09-14/     한 번의 수집인데
     *   /review_landing/dt=2026-09-15/     날짜가 바뀔 때마다
     *   /review_landing/dt=2026-09-16/     폴더가 새로 생긴다
     * </pre>
     *
     * <p>사람이 날짜를 기억했다가 네 번 돌려야 하고, <b>하나를 빠뜨리면 그날 것이
     * 통째로 빠진 채 오류도 안 난다.</b> 그래서 스스로 찾게 한다.
     *
     * <p>⚠ 오늘 날짜는 건드리지 않는다. 수집기가 아직 쓰고 있는 중일 수 있고,
     * 그 상태로 바꾸면 반쪽짜리 delta 가 「변환 완료」로 남아 다시 만들어지지
     * 않는다. 오늘 것을 굳이 지금 바꾸려면 날짜를 직접 준다.
     */
    static List<String> pending(SparkSession spark) {
        String today = TimeRule.partition(TimeRule.today());
        List<String> done = listPartitions(spark, HdfsPaths.REVIEW_DELTA);
        List<String> todo = new ArrayList<>();
        for (String dt : listPartitions(spark, HdfsPaths.REVIEW_LANDING)) {
            if (done.contains(dt)) {
                continue;
            }
            if (dt.equals(today)) {
                System.out.println("오늘(" + dt + ")은 건너뛴다 — 수집기가 아직 쓰고 있을 수 있다.");
                continue;
            }
            todo.add(dt);
        }
        return todo;
    }

    /** {@code dt=} 로 시작하는 하위 폴더의 날짜 부분만 모아 오름차순으로 준다. */
    static List<String> listPartitions(SparkSession spark, String root) {
        try {
            FileSystem fs = FileSystem.get(URI.create(HdfsPaths.HDFS),
                    spark.sparkContext().hadoopConfiguration());
            Path path = new Path(root);
            if (!fs.exists(path)) {
                return List.of();
            }
            SortedSet<String> dates = new TreeSet<>();
            for (FileStatus status : fs.listStatus(path)) {
                String name = status.getPath().getName();
                if (status.isDirectory() && name.startsWith("dt=")) {
                    dates.add(name.substring(3));
                }
            }
            return new ArrayList<>(dates);
        } catch (IOException failure) {
            throw new UncheckedIOException("HDFS 목록을 읽지 못했다: " + root, failure);
        }
    }

    /** 날짜 하나를 landing 에서 delta 로 옮긴다. */
    static void convertOne(SparkSession spark, String dt) {
        String src = HdfsPaths.reviewLandingOf(dt);
        String dst = HdfsPaths.reviewDeltaOf(dt);

        System.out.println("── " + dt + " ──────────────────────────────");
        System.out.println("읽는다  " + src);

        // ⚠ 글로브(/*.jsonl.gz) 가 아니라 재귀로 읽는다.
        //   2026-09-16 부터 수집기가 dt=날짜/시 로 한 단계 더 나눠 쓴다
        //   (HDFS 디렉터리 항목 한도 때문 — TimeRule.hourBucket 참고).
        //   그 전에 받은 것은 dt=날짜 바로 아래에 있다. 재귀로 읽으면 둘 다 잡힌다.
        //   '.' 로 시작하는 미완성 파일(.xxx.inprogress)은 스파크가 알아서 뺀다.
        Dataset<Row> raw = spark.read()
                .schema(LANDING)
                .option("recursiveFileLookup", "true")
                .json(src);
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
