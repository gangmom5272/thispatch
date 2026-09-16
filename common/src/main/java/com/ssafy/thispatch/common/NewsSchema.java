package com.ssafy.thispatch.common;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * 공지(패치노트) 원본 Parquet 스키마.
 *
 * <p>{@link ReviewSchema} 와 같은 규칙이다 — <b>칼럼명은 DB 이름을 따르고</b>,
 * 값은 받은 그대로 둔다. {@code TIMESTAMPTZ} 변환은 적재 단계에서 한다.
 *
 * <p>대조한 정본: {@code backend/src/main/resources/db/migration/V1__init.sql}
 * 의 {@code news}, 그리고 2026-09-15 스팀 ISteamNews 실측.
 *
 * <pre>
 *   스팀 응답        우리 이름 (Parquet · DB 공통)
 *   date        ->  published_ts
 *   tags        ->  feed_tags        배열을 쉼표로 합친다 (DB 가 VARCHAR(300))
 * </pre>
 *
 * <p>NewsToParquet가 원문 필드를 보존하고 공통 PatchClassifier의
 * {@code is_patch} · {@code patch_reason}을 추가한다. 판정으로 행을 걸러내지 않는다.
 * 원본 JSON은 보존하므로 규칙이 바뀌면 재수집 없이 재변환할 수 있다.
 */
public final class NewsSchema {

    private NewsSchema() {
    }

    /**
     * 조심해야 하는 필드.
     *
     * <p><b>gid</b> — 공지의 영구 번호이자 DB {@code news} 의 PK 다. 스팀이 숫자
     * 문자열로 주는데 19자리라 {@code long} 경계에 가깝다. <b>문자열로 둔다</b>
     * (DB 도 {@code VARCHAR(20)} 이다).
     *
     * <p><b>contents</b> — HTML 이 아니라 <b>BBCode</b> 다(2026-09-15 실측).
     * <pre>
     *   [p]\[ MAPS ][/p][p]Cache[/p][list][*][p]Fixed various gaps in map.[/p][/*][/list]
     * </pre>
     * 여기서는 손대지 않고 그대로 담는다. 태그를 어디까지 풀지는 분석 쪽이 정한다.
     *
     * <p><b>tags</b> — 스팀이 배열로 준다. DB 가 쉼표 구분 문자열이라 맞춘다.
     */
    public static final StructType NEWS_SOURCE = new StructType(new StructField[] {
            // 식별
            f("gid", DataTypes.StringType, false),        // 19자리. 숫자로 바꾸지 않는다
            f("appid", DataTypes.LongType, false),

            // 본문
            f("title", DataTypes.StringType, true),
            f("contents", DataTypes.StringType, true),    // BBCode
            f("url", DataTypes.StringType, true),

            // 시각 — unix 초. TIMESTAMPTZ 변환은 적재 단계에서 한다
            f("published_ts", DataTypes.LongType, true),
            f("collected_ts", DataTypes.LongType, false),

            // 출처 — 패치 판별(S15P21A202-130)이 이것들을 본다
            f("author", DataTypes.StringType, true),
            f("feedname", DataTypes.StringType, true),    // steam_community_announcements 등
            f("feedlabel", DataTypes.StringType, true),   // 사람이 읽는 이름
            f("feed_type", DataTypes.IntegerType, true),
            f("feed_tags", DataTypes.StringType, true),   // 쉼표 구분
            f("is_external_url", DataTypes.BooleanType, true),
    });

    /** Parquet 계약. 과거 판정 없는 파일을 읽으면 추가 필드는 null이며 집계에서 제외된다. */
    public static final StructType NEWS_RAW = NEWS_SOURCE
            .add("is_patch", DataTypes.BooleanType, true)
            .add("patch_reason", DataTypes.StringType, true);

    /**
     * 같은 공지를 두 번 받은 것만 하나로 줄이는 기준.
     *
     * <p>리뷰와 달리 판이 여러 개일 일이 거의 없다. 공지는 올라온 뒤 바뀌지 않고,
     * 우리가 매일 최신 100건을 받으므로 <b>어제 받은 것을 오늘 또 받는다.</b>
     * 그 완전 중복만 줄인다.
     *
     * <p>혹시 스팀이 본문을 고쳐 준다면 {@code collected_ts} 가 다른 두 행이 남는데,
     * 그건 {@link NewsLake#latest} 가 최신 하나를 고른다.
     */
    public static final String[] DEDUP_KEY = {"gid", "collected_ts"};

    private static StructField f(String name, org.apache.spark.sql.types.DataType type, boolean nullable) {
        return DataTypes.createStructField(name, type, nullable);
    }
}
