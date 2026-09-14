package com.ssafy.thispatch.common;

/**
 * HDFS 경로 규약.
 *
 * <p>각자 정하면 반드시 어긋난다. 수집 담당이 {@code /review_raw/} 에 쓰고
 * 집계 담당이 {@code /reviews/} 에서 읽으면 서로 못 찾는다. 여기 상수만 쓴다.
 *
 * <p>주소는 IP 가 아니라 이름({@code dispatch-master})으로 쓴다. 마스터 IP 는
 * 하루 안에 세 번 바뀐 적이 있고(무선 → 유선 → 고정), 그때마다 각 노드의
 * {@code /etc/hosts} 한 줄만 고쳐서 넘겼다. 코드에 IP 를 박으면 그때마다
 * 코드를 고치고 다시 배포해야 한다.
 */
public final class HdfsPaths {

    private HdfsPaths() {
    }

    public static final String HDFS = "hdfs://dispatch-master:9000";

    /**
     * 수집기가 떨구는 원본. 스팀 응답을 가공하지 않고 그대로 담는다.
     *
     * <p>Parquet 이 아니라 {@code .jsonl.gz} 다. 수집기에 하둡·parquet-mr 을
     * 다 넣지 않기 위해서이고, 원본이 JSON 이면 나중에 필요한 필드가 생겨도
     * 다시 수집하지 않고 여기서 뽑을 수 있다.
     */
    public static final String REVIEW_LANDING = HDFS + "/review_landing";

    /**
     * compaction 이 주 1회 합쳐서 다시 쓰는 본체.
     */
    public static final String REVIEW_BASE = HDFS + "/review_raw/base";

    /**
     * 매일 변환된 분. {@code dt=YYYY-MM-DD} 로 파티션한다.
     *
     * <p>base 와 나누는 이유는 파일 개수다. 수집 1회에 파일이 1,974개 나온다(실측).
     * 그대로 두면 데이터 크기와 무관하게 Spark 가 파일 여는 비용만으로 느려진다.
     */
    public static final String REVIEW_DELTA = HDFS + "/review_raw/delta";

    public static final String NEWS_LANDING = HDFS + "/news_landing";
    public static final String NEWS_RAW = HDFS + "/news_raw";

    /** AI 담당 산출물. 모양은 아직 정해지지 않았다. */
    public static final String REVIEW_TOPIC = HDFS + "/review_topic";
    public static final String EMBEDDING = HDFS + "/embeddings";

    /** Spark 이벤트 로그. 클러스터가 쓰는 곳이라 건드리지 않는다. */
    public static final String SPARK_LOGS = HDFS + "/spark-logs";

    /**
     * 그날 수집분이 떨어지는 곳. {@code dt} 는 KST 기준 날짜다.
     *
     * <p>파티션은 날짜로만 잡는다. BOOLEAN 컬럼으로 파티션하면 읽을 때
     * STRING 으로 돌아온다(실측). 파티션 값이 디렉터리 이름에서 복원되기
     * 때문이다. {@code voted_up} 같은 것은 일반 컬럼으로 둔다.
     */
    public static String reviewLandingOf(String dt) {
        return REVIEW_LANDING + "/dt=" + dt;
    }

    public static String reviewDeltaOf(String dt) {
        return REVIEW_DELTA + "/dt=" + dt;
    }

    public static String newsLandingOf(String dt) {
        return NEWS_LANDING + "/dt=" + dt;
    }

    /**
     * base + delta 를 함께 읽을 때 쓴다.
     *
     * <p>delta 가 아직 없는 날이 있으므로 세션에
     * {@code spark.sql.files.ignoreMissingFiles=true} 가 켜져 있어야 한다.
     * {@link SparkSessions} 가 켜 준다.
     */
    public static String[] reviewAll() {
        return new String[] {REVIEW_BASE, REVIEW_DELTA};
    }
}
