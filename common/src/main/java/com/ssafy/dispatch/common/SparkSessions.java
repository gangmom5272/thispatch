package com.ssafy.dispatch.common;

import org.apache.spark.sql.SparkSession;

/**
 * Spark 세션 공통 설정.
 *
 * <p>한 명만 ANSI 를 켜면 그 사람 잡만 예외로 죽는다. 한 명만 파티션 수를
 * 올리면 그 잡이 무선 링크를 혼자 먹는다. 여기로 통일한다.
 *
 * <pre>
 *   SparkSession spark = SparkSessions.build("daily_stat");
 * </pre>
 */
public final class SparkSessions {

    private SparkSessions() {
    }

    /**
     * 셔플 파티션 수.
     *
     * <p>노드 간 실측 처리량 (2026-09-10)
     *
     * <pre>
     *   무선 워커 → 마스터    3.2 MB/s
     *   유선 링크 자체       76.8 MB/s      24배 차이
     * </pre>
     *
     * <p>워커 4대가 전부 무선이고 AP 하나를 공유한다. 셔플은 전부 이 링크를 탄다.
     * 설정으로 메울 수 있는 격차가 아니라서 셔플 자체를 줄이는 수밖에 없다.
     *
     * <p>클러스터 vcore 가 30 이므로 그 2배로 잡았다. 더 올리면 과분할이다.
     */
    public static final int SHUFFLE_PARTITIONS = 60;

    public static SparkSession build(String appName) {
        return builder(appName).getOrCreate();
    }

    /**
     * 설정을 더 얹어야 하면 이걸 쓴다.
     *
     * <pre>
     *   SparkSessions.builder("compaction")
     *                .config("spark.sql.files.maxPartitionBytes", "256m")
     *                .getOrCreate();
     * </pre>
     */
    public static SparkSession.Builder builder(String appName) {
        return SparkSession.builder()
                .appName("dispatch-" + appName)

                // Spark 4 는 ANSI SQL 모드가 기본 켜짐이다. 잘못된 캐스팅이나
                // 숫자 넘침에서 null 을 돌려주는 대신 예외를 던진다.
                //
                // 스팀 JSON 은 필드 타입이 흔들린다. weighted_vote_score 가 응답마다
                // 문자열이기도 하고 실수이기도 하며, is_early_access 키가 아예 없는
                // 게임도 있다(둘 다 실측). 매일 09시 배치가 그 자리에서 멈추면
                // 그날 데이터가 통째로 빈다.
                //
                // 파이프라인이 안정될 때까지 Spark 3 동작을 유지한다.
                // 대신 캐스팅한 자리마다 null 이 몇 개 나왔는지 세서 로그에 남긴다.
                // 조용한 오염은 그렇게 잡는다.
                .config("spark.sql.ansi.enabled", "false")

                .config("spark.sql.shuffle.partitions", String.valueOf(SHUFFLE_PARTITIONS))

                // delta 가 아직 없는 날에도 base 만 읽고 넘어가게 한다
                .config("spark.sql.files.ignoreMissingFiles", "true")

                // 노트북은 절전·무선 끊김이 잦다. 즉시 실패하지 않게 한다.
                .config("spark.network.timeout", "300s");
    }
}
