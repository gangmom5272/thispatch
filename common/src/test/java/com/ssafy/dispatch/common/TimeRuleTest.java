package com.ssafy.dispatch.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시각 규약이 KST 로 자르는지 확인한다.
 *
 * <p>이게 틀리면 daily_stat 이 하루씩 밀리는데 오류가 안 나서 눈치채기 어렵다.
 * 그래서 테스트로 못 박는다.
 */
class TimeRuleTest {

    /**
     * 문서에 적어둔 바로 그 값. UTC 로 자르면 9/4, KST 로 자르면 9/5 다.
     *
     * <pre>
     *   1788577765  ->  UTC 2026-09-04 19:09  /  KST 2026-09-05 04:09
     * </pre>
     */
    private static final long UTC_EVENING_KST_NEXT_DAY = 1788577765L;

    @Test
    @DisplayName("UTC 기준으로는 전날이지만 KST 기준이면 다음 날이다")
    void cutsByKst() {
        assertEquals(LocalDate.of(2026, 9, 5), TimeRule.statDate(UTC_EVENING_KST_NEXT_DAY));
    }

    @Test
    @DisplayName("파티션 문자열은 dt= 뒤에 그대로 붙일 수 있는 모양이다")
    void partitionString() {
        assertEquals("2026-09-05", TimeRule.partition(UTC_EVENING_KST_NEXT_DAY));
    }

    @Test
    @DisplayName("하루 경계는 시작을 포함하고 끝을 포함하지 않는다")
    void dayBounds() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        long from = TimeRule.startOfDay(day);
        long to = TimeRule.endOfDayExclusive(day);

        assertEquals(86400L, to - from, "하루는 86400초다");
        assertEquals(day, TimeRule.statDate(from), "시작 시각은 그 날에 속한다");
        assertEquals(day.plusDays(1), TimeRule.statDate(to), "끝 시각은 다음 날이다");
        assertTrue(from <= UTC_EVENING_KST_NEXT_DAY && UTC_EVENING_KST_NEXT_DAY < to);
    }

    @Test
    @DisplayName("KST 자정 직전과 직후가 다른 날로 갈린다")
    void midnightBoundary() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        long lastSecond = TimeRule.endOfDayExclusive(day) - 1;

        assertEquals(day, TimeRule.statDate(lastSecond));
        assertEquals(day.plusDays(1), TimeRule.statDate(lastSecond + 1));
    }
}
