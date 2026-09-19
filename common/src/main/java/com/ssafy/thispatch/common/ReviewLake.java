package com.ssafy.thispatch.common;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.struct;

import java.util.Arrays;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * 리뷰 원본을 읽는 단 하나의 입구.
 *
 * <p>{@link HdfsPaths} 가 <b>어디에</b> 쌓이는지를 정했다면, 여기는 <b>어떻게 읽는지</b>를
 * 정한다. 둘을 같이 봐야 한 벌이 된다.
 *
 * <pre>
 *   /review_raw/base/            최초 전량 수집분 · 또는 마지막 compaction 결과
 *   /review_raw/delta/dt=날짜/    그 뒤로 받은 증분
 * </pre>
 *
 * <p><b>왜 각자 읽으면 안 되는가.</b> 같은 리뷰가 두 곳에 있을 수 있다. 사람이
 * 리뷰를 고치면 스팀이 {@code updated_ts} 만 바꿔서 같은 {@code recommendationid}
 * 를 다시 준다(실측 13.7%). base 에 옛 판이, delta 에 새 판이 남는다. 집계 담당이
 * 그냥 둘을 union 하면 <b>한 리뷰가 두 번 세어지고 오류는 안 난다.</b> 화면 숫자만
 * 조용히 틀린다.
 *
 * <p>거기에 우리 쪽 사정이 하나 더 있다. 조각이 중간에 실패하면 저장된 자리에서
 * 다시 받는데, 받다 만 게임 하나는 페이지가 겹쳐서 들어온다. 2026-09-15 전량
 * 수집에서 조각 70개가 그렇게 재개됐다. 그래서 <b>완전히 같은 레코드</b>도 섞여 있다.
 *
 * <p>정리하면 겹치는 방식이 두 가지다.
 *
 * <pre>
 *   같은 리뷰 · 같은 updated_ts     재수집으로 생긴 완전 중복   → 한 벌만 남긴다
 *   같은 리뷰 · 다른 updated_ts     사람이 고쳐서 생긴 다른 판   → 목적에 따라 다르다
 * </pre>
 *
 * <p>그래서 입구를 둘로 나눈다.
 *
 * <pre>
 *   ReviewLake.all(spark)      모든 판. 고친 이력이 필요한 집계가 쓴다
 *   ReviewLake.latest(spark)   리뷰당 최신 한 벌. 「지금 이 리뷰는 뭐라고 쓰여 있나」
 * </pre>
 *
 * <p><b>최신만 남기면 안 되는 집계가 있다.</b> {@code daily_stat.edited_review_count}
 * 는 「그날 고쳐진 리뷰가 몇 건인가」라서 옛 판이 사라지면 지난 날짜를 다시 만들
 * 수 없다. 그래서 저장은 모든 판을 들고, 최신만 고르는 일은 읽을 때 한다.
 * {@link ReviewSchema#DEDUP_KEY} 의 설명과 같은 이야기다.
 */
public final class ReviewLake {

    private ReviewLake() {
    }

    /** 최신을 고를 때의 기준 칼럼. 앞에 있는 것이 우선한다. */
    private static final String PRIMARY_ORDER = "updated_ts";
    private static final String TIE_BREAK = "collected_ts";

    /**
     * base + delta 전량. 사람이 고친 옛 판도 그대로 들어 있다.
     *
     * <p>완전히 같은 레코드(재수집으로 두 번 들어온 것)만 한 벌로 줄인다.
     * {@link ReviewSchema#DEDUP_KEY} 가 그 기준이다.
     */
    public static Dataset<Row> all(SparkSession spark) {
        return all(read(spark));
    }

    /** 이미 읽어 둔 것에 같은 규칙을 적용한다. 테스트와 compaction 이 쓴다. */
    public static Dataset<Row> all(Dataset<Row> lake) {
        return lake.dropDuplicates(ReviewSchema.DEDUP_KEY);
    }

    /**
     * 리뷰 하나당 가장 최신 한 벌.
     *
     * <p>{@code updated_ts} 가 큰 것을 고르고, 그것마저 같으면 나중에 수집한
     * ({@code collected_ts} 가 큰) 쪽을 고른다. 둘 다 같으면 어느 쪽을 골라도
     * 내용이 같다.
     */
    public static Dataset<Row> latest(SparkSession spark) {
        return latest(read(spark));
    }

    /** 이미 읽어 둔 것에 같은 규칙을 적용한다. */
    public static Dataset<Row> latest(Dataset<Row> lake) {
        // ⚠ 윈도 함수(row_number)를 쓰지 않는다.
        //
        //   row_number 는 셔플한 뒤 파티션 안에서 정렬까지 한다. 우리 워커 4대는
        //   전부 무선이고 AP 하나를 나눠 쓴다 — 노드 간 3.2 MB/s 다(2026-09-10 실측,
        //   유선의 1/24). 1.5억 건에 정렬을 얹을 여유가 없다.
        //
        //   max(struct(...)) 는 정렬이 없다. 구조체를 앞 필드부터 차례로 비교하므로
        //   기준 칼럼을 앞에 두면 그게 곧 정렬 기준이 된다. 게다가 맵 쪽에서 미리
        //   추려서 보내기 때문에 셔플로 나가는 양도 준다.
        Column[] everything = Arrays.stream(lake.columns()).map(lake::col).toArray(Column[]::new);

        Column probe = struct(
                col(PRIMARY_ORDER).as("_order"),
                col(TIE_BREAK).as("_tie"),
                struct(everything).as("_row"));

        return lake.groupBy("recommendationid")
                .agg(max(probe).as("_best"))
                .select("_best._row.*");
    }

    /**
     * base 와 delta 를 함께 읽는다.
     *
     * <p>delta 가 아직 없는 날이 있고 base 도 첫 compaction 전에는 없다.
     * {@link SparkSessions} 가 {@code spark.sql.files.ignoreMissingFiles} 를
     * 켜 두므로 없는 쪽은 건너뛴다.
     */
    public static Dataset<Row> read(SparkSession spark) {
        // ⚠ ignoreMissingFiles 는 없는 '파일' 만 봐 준다. 경로 자체가 없으면 PATH_NOT_FOUND 로 죽는다.
        //   base 는 첫 compaction 전에는 없다 — 2026-09-18 첫 compaction 이 여기서 죽었다.
        //   있는 경로만 골라 읽고, 하나도 없으면 빈 데이터셋을 준다.
        java.util.List<String> existing = new java.util.ArrayList<>();
        try {
            org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(
                    java.net.URI.create(HdfsPaths.HDFS), spark.sparkContext().hadoopConfiguration());
            for (String p : HdfsPaths.reviewAll()) {
                if (fs.exists(new org.apache.hadoop.fs.Path(p))) {
                    existing.add(p);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HDFS 를 못 읽는다: " + e.getMessage(), e);
        }
        if (existing.isEmpty()) {
            return spark.createDataFrame(new java.util.ArrayList<Row>(), ReviewSchema.REVIEW_RAW);
        }
        return spark.read().schema(ReviewSchema.REVIEW_RAW).parquet(existing.toArray(String[]::new));
    }
}
