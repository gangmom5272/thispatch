package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses dates only after the resolver has tied a sentence to the current deployment. */
final class PatchDeploymentDateParser {
    record Parsed(LocalDate date, Instant instant) {}

    private static final String MONTH = "(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\.?";
    private static final Pattern DATE = pattern("(?<![\\d.])(?:20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}|20\\d{2}년\\s*\\d{1,2}월\\s*\\d{1,2}일|"
            + MONTH + "\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?\\s+20\\d{2}|\\d{1,2}\\s+" + MONTH + "[,]?\\s+20\\d{2})(?!\\d)");
    private static final Pattern ISO = pattern("20\\d{2}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern CLOCK = pattern("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?(?![\\d:])");
    private static final Pattern ZONE = pattern("\\b(?:UTC|GMT)(?:\\s*([+-]\\d{1,2}(?::\\d{2})?))?\\b|\\b(KST|JST|PDT|PST|EDT|EST|CEST|CET)\\b");
    private static final List<DateTimeFormatter> FORMATS = List.of(format("uuuu-M-d"), format("uuuu년 M월 d일"),
            format("MMMM d uuuu"), format("MMM d uuuu"), format("d MMMM uuuu"), format("d MMM uuuu"));

    private PatchDeploymentDateParser() {}

    static boolean hasDate(String text) {
        return DATE.matcher(text).find();
    }

    static String bindMaintenanceTitleDate(String title, String sentence) {
        var titleDate = pattern("(20\\d{2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})").matcher(title);
        var bodyDate = pattern("(\\d{1,2})/(\\d{1,2})/(20\\d{2})").matcher(sentence);
        if (!titleDate.find() || !bodyDate.find()) return sentence;
        // The same full date in the title disambiguates the publisher's month/day notation.
        if (Integer.parseInt(titleDate.group(1)) != Integer.parseInt(bodyDate.group(3))
                || Integer.parseInt(titleDate.group(2)) != Integer.parseInt(bodyDate.group(1))
                || Integer.parseInt(titleDate.group(3)) != Integer.parseInt(bodyDate.group(2))) return sentence;
        String normalized = titleDate.group(1) + "-" + titleDate.group(2) + "-" + titleDate.group(3);
        return sentence.substring(0, bodyDate.start()) + normalized + sentence.substring(bodyDate.end());
    }

    static Parsed parse(String text, ZoneId verifiedZone) {
        try {
            var iso = ISO.matcher(text);
            if (iso.find()) {
                Instant instant = java.time.OffsetDateTime.parse(iso.group()).toInstant();
                if (iso.find()) return null;
                return new Parsed(TimeRule.statDate(instant.getEpochSecond()), instant);
            }
            if (pattern("\\b(?:AM|PM)\\b|\\dT\\d").matcher(text).find()) return null;
            var dates = DATE.matcher(text);
            if (!dates.find()) return null;
            String matchedDate = dates.group();
            if (matchedDate.matches("20\\d{2}\\.\\d{1,2}\\.\\d{1,2}")) {
                String prefix = text.substring(0, dates.start());
                // Year-shaped software versions (e.g. VRChat 2021.2.4) are not calendar evidence.
                if (!pattern("(?:\\b(?:on|at)|적용일|배포일|업데이트일)\\s*$").matcher(prefix).find()) return null;
            }
            String dateText = dates.group().replaceAll("(?i)(\\d)(st|nd|rd|th)", "$1").replace(",", "")
                    .replaceAll("(?<=[A-Za-z])\\.", "").replace('/', '-').replace('.', '-').replaceAll("\\s+", " ");
            if (dates.find()) return null; // Several events need separate evidence, not the first date.
            LocalDate date = parseDay(dateText);
            if (date == null) return null;
            ZoneId zone = verifiedZone;
            var zones = ZONE.matcher(text);
            ZoneId explicitZone = null;
            while (zones.find()) {
                ZoneId next = offset(zones.group(1), zones.group(2));
                if (explicitZone != null && !explicitZone.equals(next)) return null;
                explicitZone = next;
            }
            if (explicitZone != null) zone = explicitZone;
            if (zone == null) return null;
            var clock = CLOCK.matcher(text);
            if (clock.find()) {
                // AM/PM and multiple clocks are intentionally held until their semantics are supported.
                if (pattern("\\b(?:AM|PM)\\b").matcher(text).find()) return null;
                LocalTime time = LocalTime.of(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)),
                        clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3)));
                if (clock.find()) return null;
                Instant instant = date.atTime(time).atZone(zone).toInstant();
                return new Parsed(TimeRule.statDate(instant.getEpochSecond()), instant);
            }
            if (pattern("\\d:").matcher(text).find()) return null;
            if (date.atStartOfDay(zone).toEpochSecond() != TimeRule.startOfDay(date)
                    || date.plusDays(1).atStartOfDay(zone).toEpochSecond() != TimeRule.endOfDayExclusive(date)) return null;
            return new Parsed(date, null);
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    private static ZoneId offset(String numeric, String abbreviation) {
        if (numeric != null) return ZoneOffset.of(numeric.length() == 2 ? numeric.charAt(0) + "0" + numeric.substring(1) : numeric);
        if (abbreviation == null) return ZoneOffset.UTC;
        return ZoneOffset.ofHours(switch (abbreviation.toUpperCase(Locale.ROOT)) {
            case "KST", "JST" -> 9;
            case "PDT" -> -7;
            case "PST" -> -8;
            case "EDT" -> -4;
            case "EST" -> -5;
            case "CEST" -> 2;
            case "CET" -> 1;
            default -> throw new IllegalArgumentException("Unsupported timezone");
        });
    }

    private static LocalDate parseDay(String text) {
        for (DateTimeFormatter format : FORMATS) {
            try { return LocalDate.parse(text, format); }
            catch (DateTimeException ignored) { /* Try the next explicit format. */ }
        }
        return null;
    }

    private static DateTimeFormatter format(String text) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(text)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    private static Pattern pattern(String text) {
        return Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
