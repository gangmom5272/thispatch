package com.ssafy.thispatch.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 시각 규약 — 셋이 같은 기준으로 하루를 자른다.
 *
 * <p>이게 어긋나면 {@code daily_stat} 이 하루씩 밀린다. 그리고 오류가 안 나서
 * 눈치채기 어렵다.
 *
 * <p><b>왜 정해야 하나</b>
 *
 * <pre>
 *   스팀 API      timestamp_created 를 unix 초로 준다
 *   HDFS 원본     받은 그대로 unix 초(long)로 담는다
 *   PostgreSQL    created_ts 가 TIMESTAMPTZ 다 (UTC 로 저장된다)
 *   daily_stat    stat_date 가 DATE 다        ← 여기서 "하루"를 잘라야 한다
 * </pre>
 *
 * <p>같은 값이 기준에 따라 다른 날이 된다.
 *
 * <pre>
 *   unix 초 1788577765
 *     UTC 로 보면   2026-09-04 19:09   ->  9/4 집계
 *     KST 로 보면   2026-09-05 04:09   ->  9/5 집계
 * </pre>
 *
 * <p><b>결정</b>: 한국 사용자를 위한 서비스이고 배치도 09시 KST 에 돈다.
 * KST 로 자른다. (2026-09-11 확정)
 */
public final class TimeRule {

    private TimeRule() {
    }

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * unix 초를 KST 기준 날짜로.
     *
     * <p>{@code daily_stat.stat_date} · {@code patch_stat.stat_date} 는
     * 반드시 이것으로 만든다.
     */
    public static LocalDate statDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZONE).toLocalDate();
    }

    /** 파티션 디렉터리에 쓰는 문자열. {@code dt=2026-09-11} 의 뒷부분. */
    public static String partition(long epochSeconds) {
        return statDate(epochSeconds).toString();
    }

    public static String partition(LocalDate date) {
        return date.toString();
    }

    /** KST 하루의 시작 (그 날 00:00:00 의 unix 초). */
    public static long startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toEpochSecond();
    }

    /**
     * KST 하루의 끝. <b>이 값은 포함하지 않는다.</b>
     *
     * <pre>
     *   startOfDay(d) &lt;= ts &lt; endOfDayExclusive(d)
     * </pre>
     */
    public static long endOfDayExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
    }

    /** 오늘(KST). 배치가 09시에 돌므로 그날 날짜가 나온다. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
