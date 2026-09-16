package com.ssafy.thispatch.spark;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PatchDateResolverTest {
    @Test void publicationDateChangesAtKstMidnight() {
        assertEquals(LocalDate.of(2026, 9, 14),
                PatchDateResolver.resolve(Instant.parse("2026-09-14T14:59:59Z")));
        assertEquals(LocalDate.of(2026, 9, 15),
                PatchDateResolver.resolve(Instant.parse("2026-09-14T15:00:00Z")));
        assertEquals(LocalDate.of(2027, 1, 1),
                PatchDateResolver.resolve(Instant.parse("2026-12-31T15:00:00Z")));
    }

    @Test void missingPublicationTimeIsNotReplacedWithToday() {
        assertThrows(NullPointerException.class, () -> PatchDateResolver.resolve(null));
    }
}
