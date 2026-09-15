package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the current patch's deployment date without substituting its publication time. */
public final class PatchDateResolver {
    public static final String RULE_VERSION = "patch-date-rules-1";

    public enum Status { RESOLVED, REVIEW_REQUIRED, NOT_APPLICABLE }
    public enum Source { EXPLICIT_TIMESTAMP, EXPLICIT_DATE, RELATIVE_DATE, NONE }

    /** patchDate is always KST. appliedAt is null when only the calendar day is known. */
    public record Result(Status status, LocalDate patchDate, Instant appliedAt,
                         Source source, String reason, String evidence) {}

    private record Candidate(LocalDate date, Instant instant, Source source, String evidence) {}

    private static final Pattern ENGLISH_DEPLOYMENT = pattern(
            "^(?:this|the) (?:patch|update|hotfix) (?:was released|was deployed|went live|has been released) (?:on |at )?(.+?)[.!]?$"
            + "|^(?:this|the) (?:patch|update|hotfix) (?:is now live|is now available) (today|yesterday)[.!]?$");
    private static final Pattern KOREAN_DEPLOYMENT = pattern(
            "^(?:이번 |해당 )?(?:패치|업데이트)(?:는|가)? (.+?)(?:에)? (?:적용되었습니다|배포되었습니다|적용 완료|배포 완료)[.!]?$");
    private static final Pattern CONTEXT_REQUIRING_REVIEW = pattern(
            "\\b(?:previous|earlier|recap|retrospective|history|planned|upcoming|roadmap|beta|test|experimental|unstable|console|mobile)\\b"
            + "|이전|지난|회고|예정|테스트|모바일|콘솔");
    private static final Pattern CURRENT_PATCH_PENDING = pattern(
            "\\b(?:this|the) (?:patch|update|hotfix) (?:will|won['’]t|is not|has not|is scheduled|is delayed|is postponed)\\b"
            + "|\\bthese patch notes will not go live\\b"
            + "|(?:이번 |해당 )?(?:패치|업데이트)(?:는|가)?.{0,60}(?:예정|연기|취소|미적용)");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            dateFormat("MMMM d, uuuu"), dateFormat("MMM d, uuuu"),
            dateFormat("d MMMM uuuu"), dateFormat("d MMM uuuu"),
            dateFormat("uuuu년 M월 d일"));

    private PatchDateResolver() {}

    /**
     * classification must describe these same title/contents. announcementZone is supplied from
     * verified publisher context; it must not be inferred from language or Steam's Unix timestamp.
     * A non-KST calendar day cannot identify one KST day without a deployment time.
     */
    public static Result resolve(PatchClassifier.Result classification, String title, String contents,
                                 Instant publishedAt, ZoneId announcementZone) {
        Objects.requireNonNull(classification, "classification");
        if (classification.decision() == PatchClassifier.Decision.NOT_PATCH) {
            return unresolved(Status.NOT_APPLICABLE, "NOT_PATCH", classification.evidence());
        }
        if (classification.decision() != PatchClassifier.Decision.PATCH) {
            return review("PATCH_NOT_CONFIRMED", classification.evidence());
        }
        if (classification.scope() != PatchClassifier.Scope.DEFAULT) {
            return review("DEPLOYMENT_SCOPE_REQUIRES_VERIFICATION", classification.evidence());
        }
        if (title == null || title.isBlank() || contents == null || contents.isBlank()) {
            return review("MISSING_TITLE_OR_BODY", "");
        }

        String cleanTitle = PatchClassifier.plainText(title).strip();
        String cleanBody = PatchClassifier.plainText(contents);
        Matcher pending = CURRENT_PATCH_PENDING.matcher(cleanTitle + "\n" + cleanBody);
        if (pending.find()) {
            return review("DEPLOYMENT_PENDING_OR_CONFLICTING", pending.group());
        }

        List<Candidate> candidates = new ArrayList<>();
        List<PatchChangeSectioner.Section> sections = new ArrayList<>();
        sections.add(new PatchChangeSectioner.Section("", cleanTitle));
        sections.addAll(PatchChangeSectioner.split(contents));
        for (PatchChangeSectioner.Section section : sections) {
            String precedingSentence = "";
            // Sentence boundaries preserve ISO timestamps and decimal version numbers.
            for (String sentence : section.text().split("[\\r\\n]+|(?<=[.!?])\\s+")) {
                String evidence = sentence.strip();
                if (evidence.isEmpty()) continue;
                String dateText = deploymentDateText(evidence);
                String context = cleanTitle + "\n" + section.headingPath() + "\n" + precedingSentence;
                precedingSentence = evidence;
                if (dateText == null) continue;
                if (CONTEXT_REQUIRING_REVIEW.matcher(context).find()) {
                    return review("DEPLOYMENT_CONTEXT_REQUIRES_VERIFICATION", evidence);
                }

                Candidate candidate = parseCandidate(dateText, evidence, publishedAt, announcementZone);
                if (candidate == null) {
                    return review("DATE_OR_TIMEZONE_UNRESOLVED", evidence);
                }
                if (publishedAt != null) {
                    boolean afterPublication = candidate.date().isAfter(TimeRule.statDate(publishedAt.getEpochSecond()));
                    if (candidate.instant() != null) {
                        afterPublication = candidate.instant().isAfter(publishedAt);
                    }
                    if (afterPublication) {
                        return review("DEPLOYMENT_AFTER_PUBLICATION", evidence);
                    }
                }
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return review("NO_EXPLICIT_DEPLOYMENT_DATE", "");
        }

        Candidate selected = candidates.get(0);
        for (Candidate candidate : candidates) {
            if (!selected.date().equals(candidate.date()) || (selected.instant() != null
                    && candidate.instant() != null && !selected.instant().equals(candidate.instant()))) {
                return review("CONFLICTING_DEPLOYMENT_DATES", joinEvidence(candidates));
            }
            if (candidate.instant() != null) selected = candidate;
        }
        return new Result(Status.RESOLVED, selected.date(), selected.instant(), selected.source(),
                "DEPLOYMENT_DATE_RESOLVED", joinEvidence(candidates));
    }

    private static String deploymentDateText(String sentence) {
        Matcher english = ENGLISH_DEPLOYMENT.matcher(sentence);
        if (english.matches()) {
            return english.group(1) != null ? english.group(1) : english.group(2);
        }
        Matcher korean = KOREAN_DEPLOYMENT.matcher(sentence);
        return korean.matches() ? korean.group(1) : null;
    }

    private static Candidate parseCandidate(String text, String evidence, Instant publishedAt,
                                            ZoneId announcementZone) {
        text = text.strip();
        try {
            Instant instant = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            return new Candidate(TimeRule.statDate(instant.getEpochSecond()), instant,
                    Source.EXPLICIT_TIMESTAMP, evidence);
        } catch (DateTimeException ignored) {
            // A calendar day has lower precision and is handled separately below.
        }

        // A publisher's local date may span two KST days. Do not choose one by inventing midnight.
        if (announcementZone == null) return null;
        String relativeDate = text.toLowerCase(Locale.ROOT);
        if (List.of("today", "yesterday", "오늘", "어제").contains(relativeDate)) {
            if (publishedAt == null) return null;
            LocalDate publicationDate = publishedAt.atZone(announcementZone).toLocalDate();
            boolean yesterday = relativeDate.equals("yesterday") || relativeDate.equals("어제");
            LocalDate deploymentDate = yesterday ? publicationDate.minusDays(1) : publicationDate;
            return calendarCandidate(deploymentDate, announcementZone, Source.RELATIVE_DATE, evidence);
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return calendarCandidate(LocalDate.parse(text, format), announcementZone, Source.EXPLICIT_DATE, evidence);
            } catch (DateTimeException ignored) {
                // Try only the documented unambiguous formats; never infer a missing year.
            }
        }
        return null;
    }

    private static Candidate calendarCandidate(LocalDate date, ZoneId zone, Source source, String evidence) {
        // Compare this day's boundaries, so verified UTC+09:00 works without assuming historical rules match.
        long localStart = date.atStartOfDay(zone).toEpochSecond();
        long localEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        if (localStart != TimeRule.startOfDay(date) || localEnd != TimeRule.endOfDayExclusive(date)) {
            return null;
        }
        return new Candidate(date, null, source, evidence);
    }

    private static String joinEvidence(List<Candidate> candidates) {
        return String.join("\n", candidates.stream().map(Candidate::evidence).distinct().toList());
    }

    private static Result review(String reason, String evidence) {
        return unresolved(Status.REVIEW_REQUIRED, reason, evidence);
    }

    private static Result unresolved(Status status, String reason, String evidence) {
        return new Result(status, null, null, Source.NONE, reason, evidence);
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static DateTimeFormatter dateFormat(String expression) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(expression)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }
}
