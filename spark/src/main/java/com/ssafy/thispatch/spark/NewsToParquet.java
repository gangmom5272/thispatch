package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.array_join;
import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.NewsSchema;
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
 * 공지 수집기가 떨군 {@code .jsonl.gz} 를 Parquet 으로 바꾼다.
 *
 * <pre>
 *   /news_landing/dt=2026-09-16/*.jsonl.gz   ->   /news_raw/dt=2026-09-16/
 * </pre>
 *
 * <p>{@link JsonToParquet} 의 공지판이다. 리뷰 쪽에서 겪은 것을 그대로 가져왔다 —
 * 타입을 추론에 맡기지 않고, 캐스팅 뒤 null 을 세고, 날짜를 스스로 찾는다.
 *
 * <p>실행
 *
 * <pre>
 *   spark-submit --class com.ssafy.thispatch.spark.NewsToParquet thispatch-spark.jar
 *       아직 news_raw 에 없는 landing 날짜를 전부 (오늘 것은 빼고)
 *
 *   ... thispatch-spark.jar 2026-09-16      그 날짜만
 *   ... thispatch-spark.jar --all           처음부터 다시
 * </pre>
 *
 * <p>공통 PatchClassifier로 is_patch와 patch_reason을 채운다.
 * 판정으로 행을 제외하지 않고 수집 이력과 원본 JSON을 보존한다.
 */
public final class NewsToParquet {

    private NewsToParquet() {
    }

    /**
     * 원본을 읽을 때 쓰는 스키마.
     *
     * <p>2026-09-15 실측으로 스팀이 주는 필드는 12개다. 여기에 수집기가
     * {@code appid} 와 {@code collected_ts} 를 각 줄에 넣는다.
     *
     * <p>⚠ {@code gid} 는 문자열로 받는다. 19자리 숫자 문자열이라 long 경계에
     * 가깝고, DB 도 {@code VARCHAR(20)} 이다.
     *
     * <p>⚠ {@code tags} 는 배열이다. DB 의 {@code feed_tags} 가 쉼표 구분
     * 문자열이라 합쳐서 담는다.
     */
    private static final StructType LANDING = new StructType()
            .add("gid", DataTypes.StringType, true)
            .add("appid", DataTypes.LongType, true)
            .add("title", DataTypes.StringType, true)
            .add("contents", DataTypes.StringType, true)
            .add("url", DataTypes.StringType, true)
            .add("author", DataTypes.StringType, true)
            .add("feedname", DataTypes.StringType, true)
            .add("feedlabel", DataTypes.StringType, true)
            .add("feed_type", DataTypes.IntegerType, true)
            .add("date", DataTypes.LongType, true)
            .add("tags", DataTypes.createArrayType(DataTypes.StringType), true)
            .add("is_external_url", DataTypes.BooleanType, true)
            .add("collected_ts", DataTypes.LongType, true);

    /** 캐스팅해서 null 이 될 수 있는 칼럼. 몇 개 나왔는지 세서 로그에 남긴다. */
    private static final String[] WATCH = {"gid", "appid", "published_ts", "collected_ts", "contents"};

    public static void main(String[] args) {
        String arg = (args.length > 0 && !args[0].isBlank()) ? args[0].trim() : "";

        SparkSession spark = SparkSessions.build("news-to-parquet");
        try {
            List<String> dates;
            if (arg.isEmpty()) {
                dates = pending(spark);
                if (dates.isEmpty()) {
                    System.out.println("변환할 것이 없다. landing 의 날짜가 전부 news_raw 에 있다.");
                    return;
                }
                System.out.println("변환 안 된 날짜 " + dates.size() + "개: " + dates);
            } else if ("--all".equals(arg)) {
                dates = listPartitions(spark, HdfsPaths.NEWS_LANDING);
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
     * 아직 {@code news_raw} 에 없는 landing 날짜.
     *
     * <p>⚠ 오늘 날짜는 건드리지 않는다. 수집기가 아직 쓰고 있을 수 있고, 그 상태로
     * 바꾸면 반쪽짜리가 「변환 완료」로 남아 다시 만들어지지 않는다.
     */
    static List<String> pending(SparkSession spark) {
        String today = TimeRule.partition(TimeRule.today());
        List<String> done = listPartitions(spark, HdfsPaths.NEWS_RAW);
        List<String> todo = new ArrayList<>();
        for (String dt : listPartitions(spark, HdfsPaths.NEWS_LANDING)) {
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

    static void convertOne(SparkSession spark, String dt) {
        String src = HdfsPaths.newsLandingOf(dt);
        String dst = HdfsPaths.newsRawOf(dt);

        System.out.println("── " + dt + " ──────────────────────────────");
        System.out.println("읽는다  " + src);

        // ⚠ JsonToParquet 과 같은 이유로 재귀로 읽는다.
        //   2026-09-16 이전 것은 dt=날짜 바로 아래, 이후는 dt=날짜/시 아래에 있다.
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
        reportNulls(mapped, inCount);

        // 같은 공지를 같은 날 두 번 받은 것만 줄인다.
        // 날짜가 다른 같은 공지는 여기서 지우지 않는다 — NewsLake.latest 가 고른다.
        Dataset<Row> deduped = mapped.dropDuplicates(NewsSchema.DEDUP_KEY);
        long outCount = deduped.count();
        System.out.println("중복 제거 후 " + outCount + "건  (" + (inCount - outCount) + "건 줄었다)");

        // 공지는 리뷰보다 훨씬 작다. 파일을 잘게 두면 여는 비용만 든다.
        // 무선이라 셔플이 비싸므로 repartition 이 아니라 coalesce 를 쓴다.
        int files = Math.max(1, (int) Math.min(8, outCount / 200_000 + 1));

        deduped.coalesce(files)
                .write()
                .mode(SaveMode.Overwrite)
                .parquet(dst);

        System.out.println("썼다    " + dst + "  (파일 " + files + "개)");
    }

    /**
     * 스팀 필드명을 우리 이름으로 바꾼다.
     *
     * <p>칼럼명은 DB({@code news})를 따른다. 값은 받은 그대로 두고
     * {@code TIMESTAMPTZ} 변환은 적재 단계에서 한다.
     */
    static Dataset<Row> toOurShape(Dataset<Row> raw) {
        Dataset<Row> mapped = raw.select(
                col("gid"),
                col("appid"),
                col("title"),
                col("contents"),
                col("url"),

                col("date").as("published_ts"),
                col("collected_ts"),

                col("author"),
                col("feedname"),
                col("feedlabel"),
                col("feed_type"),
                // ⚠ 배열을 쉼표로 합친다. DB 의 feed_tags 가 VARCHAR(300) 이다.
                //   null 배열은 array_join 이 null 을 주므로 그대로 둔다.
                array_join(coalesce(col("tags"), lit(null).cast("array<string>")), ",")
                        .as("feed_tags"),
                col("is_external_url"));
        // 최신 공지 선택은 읽는 쪽에서 한다. 변환 단계에서는 수집 이력을 유지한다.
        Dataset<Row> classified = PatchClassificationProcessor.classifyRows(mapped);
        return classified.selectExpr(NewsSchema.NEWS_RAW.fieldNames());
    }

    /**
     * 캐스팅 뒤 null 이 몇 개 생겼는지 센다.
     *
     * <p>ANSI 를 껐으므로 타입이 안 맞으면 예외 대신 null 이 된다. 세지 않으면
     * 조용히 오염된다.
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
            System.out.printf("  %-16s %6d건  %5.1f%%  <- 확인 필요%n", c, n, 100.0 * n / total);
        }
        System.out.println("--------------------------------------------");
    }
}
