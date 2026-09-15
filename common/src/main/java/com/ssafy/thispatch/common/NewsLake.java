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
 * 공지 원본을 읽는 단 하나의 입구. {@link ReviewLake} 의 공지판이다.
 *
 * <p><b>왜 필요한가.</b> 우리는 매일 게임마다 <b>최신 100건</b>을 받는다. 공지가
 * 새로 올라오지 않은 게임은 어제 받은 것을 오늘 또 받는다. 날짜 파티션을 그냥
 * 다 읽으면 <b>같은 공지가 날짜 수만큼 세어진다.</b> 오류는 안 난다.
 *
 * <pre>
 *   /news_raw/dt=2026-09-16/   gid 1843481262690556  "CS2 Update"
 *   /news_raw/dt=2026-09-17/   gid 1843481262690556  "CS2 Update"   ← 같은 것
 * </pre>
 *
 * <p>리뷰와 달리 <b>base/delta 로 나누지 않는다.</b> 공지는 수정되지 않고 양도
 * 작아서(전체 약 1천만 행) 날짜 파티션을 전부 읽어도 부담이 없다.
 */
public final class NewsLake {

    private NewsLake() {
    }

    /** 같은 날 두 번 받은 것만 줄인다. 날짜가 다른 같은 공지는 그대로 남는다. */
    public static Dataset<Row> all(SparkSession spark) {
        return all(read(spark));
    }

    public static Dataset<Row> all(Dataset<Row> lake) {
        return lake.dropDuplicates(NewsSchema.DEDUP_KEY);
    }

    /**
     * 공지 하나당 한 벌. 보통 이것을 쓴다.
     *
     * <p>{@code gid} 로 묶고 나중에 받은({@code collected_ts} 가 큰) 쪽을 고른다.
     * 스팀이 본문을 고쳐 줬다면 새로 받은 쪽이 맞다.
     */
    public static Dataset<Row> latest(SparkSession spark) {
        return latest(read(spark));
    }

    public static Dataset<Row> latest(Dataset<Row> lake) {
        // ⚠ ReviewLake 와 같은 이유로 윈도 함수를 쓰지 않는다 — 셔플 뒤 정렬이 붙는다.
        //   max(struct(...)) 는 정렬이 없고 맵 쪽에서 미리 추려 보낸다.
        Column[] everything = Arrays.stream(lake.columns()).map(lake::col).toArray(Column[]::new);

        Column probe = struct(
                col("collected_ts").as("_order"),
                struct(everything).as("_row"));

        return lake.groupBy("gid")
                .agg(max(probe).as("_best"))
                .select("_best._row.*");
    }

    /**
     * 날짜 파티션을 전부 읽는다.
     *
     * <p>아직 하루치도 없을 수 있다. {@link SparkSessions} 가
     * {@code spark.sql.files.ignoreMissingFiles} 를 켜 둔다.
     */
    public static Dataset<Row> read(SparkSession spark) {
        return spark.read().schema(NewsSchema.NEWS_RAW).parquet(HdfsPaths.NEWS_RAW);
    }
}
