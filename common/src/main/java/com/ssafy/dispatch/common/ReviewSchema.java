package com.ssafy.dispatch.common;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * 리뷰 원본 Parquet 스키마.
 *
 * <p>각자 정의하면 어긋난다. 수집 담당이 {@code appid} 를 문자열로 쓰고
 * 집계 담당이 숫자로 읽으면 <b>조인이 조용히 0건</b>이 된다. 오류도 안 난다.
 *
 * <p><b>칼럼명은 DB 이름을 따른다</b> (2026-09-11 확정). 스팀이 주는 이름과
 * 다른 것이 있는데, 이름이 하나여야 변환할 때 헷갈리지 않는다.
 *
 * <pre>
 *   스팀 응답              우리 이름 (Parquet · DB 공통)
 *   recommendationid  ->  recommendationid
 *   timestamp_created ->  created_ts
 *   timestamp_updated ->  updated_ts
 *   review            ->  review_text
 *   language          ->  language_code
 * </pre>
 *
 * <p><b>값은 받은 그대로 둔다.</b> {@code created_ts} 는 여기서 unix 초(long)이고,
 * PostgreSQL 에 넣을 때만 {@code TIMESTAMPTZ} 로 바꾼다. 이름만 맞추고
 * 타입 변환은 적재 단계에서 한다.
 *
 * <p>대조한 정본: {@code backend/src/main/resources/db/migration/V1__init.sql}
 * 의 {@code recent_review}, 그리고 2026-09-05 스팀 API 실측 조사.
 */
public final class ReviewSchema {

    private ReviewSchema() {
    }

    /**
     * 조심해야 하는 필드 둘.
     *
     * <p><b>recommendationid</b> — 스팀이 {@code "234499291"} 처럼 숫자 문자열로 준다.
     * {@code long} 으로 바꿔 담는다. 문자열로 두면 {@code recent_review} 와
     * 조인할 때 어긋난다.
     *
     * <p><b>weighted_vote_score</b> — 같은 필드인데 응답마다 문자열이기도 하고
     * 실수이기도 하다(실측). {@code double} 로 고정하고 읽을 때 명시적으로
     * 캐스팅한다. Spark 4 의 ANSI 모드가 켜져 있으면 여기서 배치가 통째로
     * 죽으므로 {@link SparkSessions} 가 ANSI 를 끈다.
     */
    public static final StructType REVIEW_RAW = new StructType(new StructField[] {
            // 식별
            f("recommendationid", DataTypes.LongType, false),
            f("appid", DataTypes.LongType, false),
            f("steam_id", DataTypes.StringType, true),   // 17자리. 숫자로 바꾸지 않는다

            // 본문
            f("review_text", DataTypes.StringType, true),
            f("language_code", DataTypes.StringType, false),

            // 시각 — unix 초. TIMESTAMPTZ 변환은 적재 단계에서 한다
            f("created_ts", DataTypes.LongType, false),
            f("updated_ts", DataTypes.LongType, false),  // 수정 한 번에 한 번 바뀐다

            // 평가
            f("voted_up", DataTypes.BooleanType, false),
            f("votes_up", DataTypes.IntegerType, true),
            f("votes_funny", DataTypes.IntegerType, true),
            f("weighted_vote_score", DataTypes.DoubleType, true),
            f("comment_count", DataTypes.IntegerType, true),

            // 구매 맥락
            f("steam_purchase", DataTypes.BooleanType, true),
            f("received_for_free", DataTypes.BooleanType, true),
            f("refunded", DataTypes.BooleanType, true),
            f("written_during_early_access", DataTypes.BooleanType, true),
            f("primarily_steam_deck", DataTypes.BooleanType, true),

            // 작성자 — 밴드(플레이타임 4분위) 계산에 쓴다
            f("playtime_at_review", DataTypes.IntegerType, true),   // 분. 결측 허용
            f("playtime_forever", DataTypes.IntegerType, true),
            f("playtime_last_two_weeks", DataTypes.IntegerType, true),
            f("num_games_owned", DataTypes.IntegerType, true),
            f("num_reviews", DataTypes.IntegerType, true),
            f("last_played", DataTypes.LongType, true),

            // 우리가 붙인다
            f("collected_ts", DataTypes.LongType, false),   // 언제 수집했나. 재현·디버깅용
    });

    /**
     * 같은 리뷰의 같은 버전인지 판단하는 기준. (2026-09-10 확정)
     *
     * <p><b>버전은 지우지 않는다.</b> 9/1 작성 · 9/5 수정 · 9/9 재수정된 리뷰는
     * 세 벌이 다 남는다. 최신만 남기면 9/5 를 영구히 놓치고, 그러면 9/5 의
     * {@code edited_review_count} 를 다시 만들 수 없다. 보존 비용은 연 8.7GB 이고
     * 디스크는 4.92TB 있다.
     *
     * <p>그래서 compaction 이 하는 일은 이력 정리가 아니라 <b>파일 통합</b>이다.
     *
     * <p>{@code recent_review} 의 {@code (recommendationid, updated_ts)} 와 같은 규칙이다.
     */
    public static final String[] DEDUP_KEY = {"recommendationid", "updated_ts"};

    private static StructField f(String name, org.apache.spark.sql.types.DataType type, boolean nullable) {
        return DataTypes.createStructField(name, type, nullable);
    }
}
