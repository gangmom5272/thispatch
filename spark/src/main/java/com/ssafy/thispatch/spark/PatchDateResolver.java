package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Uses the announcement's publication date as the patch date in KST. */
public final class PatchDateResolver {
    public static final String RULE_VERSION = "patch-date-publication-3";

    private PatchDateResolver() {}

    public static LocalDate resolve(Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        return TimeRule.statDate(publishedAt.getEpochSecond());
    }
}
