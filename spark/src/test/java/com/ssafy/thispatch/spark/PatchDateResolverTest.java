package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatchDateResolverTest {
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-15T01:00:00Z");

    @Test
    void explicitTimestampUsesKstDateAndPreservesActualInstant() {
        var result = resolve("This patch was deployed at 2026-09-14T16:30:00Z.", null);

        assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertEquals(LocalDate.of(2026, 9, 15), result.patchDate());
        assertEquals(Instant.parse("2026-09-14T16:30:00Z"), result.appliedAt());
        assertEquals(PatchDateResolver.Source.EXPLICIT_TIMESTAMP, result.source());
    }

    @Test
    void offsetTimestampDoesNotRequirePublisherTimezone() {
        var result = resolve("The update went live on 2026-09-14T08:30:00-07:00.", null);

        assertEquals(LocalDate.of(2026, 9, 15), result.patchDate());
        assertEquals(Instant.parse("2026-09-14T15:30:00Z"), result.appliedAt());
    }

    @Test
    void supportedFullDatesPreserveDayPrecision() {
        for (String date : List.of("2026-09-14", "September 14, 2026", "Sep 14, 2026",
                "14 September 2026", "14 Sep 2026")) {
            var result = resolve("This patch was released on " + date + ".");

            assertEquals(PatchDateResolver.Status.RESOLVED, result.status(), date);
            assertEquals(LocalDate.of(2026, 9, 14), result.patchDate(), date);
            assertNull(result.appliedAt(), "Do not invent a deployment time for " + date);
            assertEquals(PatchDateResolver.Source.EXPLICIT_DATE, result.source());
        }
    }

    @Test
    void koreanCompletedDeploymentSupportsFullAndRelativeDates() {
        for (String sentence : List.of("이번 패치는 2026년 9월 14일에 적용되었습니다.",
                "업데이트 2026-09-14 배포 완료", "해당 패치가 어제 적용되었습니다.")) {
            var result = resolve(sentence);

            assertEquals(PatchDateResolver.Status.RESOLVED, result.status(), sentence);
            assertEquals(LocalDate.of(2026, 9, 14), result.patchDate());
        }
    }

    @Test
    void relativeDatesUsePublicationDayAcrossKstAndYearBoundary() {
        Instant published = Instant.parse("2025-12-31T15:05:00Z");
        var today = resolve("The patch is now live today!", published, TimeRule.ZONE);
        var yesterday = resolve("This patch was deployed yesterday.", published, TimeRule.ZONE);

        assertEquals(LocalDate.of(2026, 1, 1), today.patchDate());
        assertEquals(LocalDate.of(2025, 12, 31), yesterday.patchDate());
        assertEquals(PatchDateResolver.Source.RELATIVE_DATE, today.source());
        assertNull(today.appliedAt());
    }

    @Test
    void missingPublicationTimeCannotAnchorRelativeDate() {
        assertReview(resolve("This patch was deployed today.", null, TimeRule.ZONE),
                "DATE_OR_TIMEZONE_UNRESOLVED");
    }

    @Test
    void calendarDateRequiresVerifiedKstPublisherContext() {
        for (String text : List.of("2026-09-14", "today", "yesterday")) {
            String body = "This patch was released on " + text + ".";
            assertReview(resolve(body, null), "DATE_OR_TIMEZONE_UNRESOLVED");
            assertReview(resolve(body, ZoneId.of("America/Los_Angeles")), "DATE_OR_TIMEZONE_UNRESOLVED");
        }
    }

    @Test
    void verifiedFixedKstOffsetCanResolveCalendarDay() {
        var result = resolve("This patch was released on 2026-09-14.", ZoneId.of("+09:00"));

        assertEquals(LocalDate.of(2026, 9, 14), result.patchDate());
        assertNull(result.appliedAt());
    }

    @Test
    void publicationDateAndNowLiveAreNotDeploymentDateEvidence() {
        for (String body : List.of("Fixed a crash.",
                "Patch Notes - September 14, 2026\nFixed a crash.", "Released: 2026-09-14")) {
            assertReview(resolve(body), "NO_EXPLICIT_DEPLOYMENT_DATE");
        }
    }

    @Test
    void completedPatchWithoutDateIsExplicitlyEstimatedFromPublication() {
        for (String body : List.of("This patch is now live!", "The update is now available.",
                "Fixed a crash.\nImproved loading speed.")) {
            var result = resolve(body, null);
            assertEquals(PatchDateResolver.Status.ESTIMATED, result.status());
            assertEquals(LocalDate.of(2026, 9, 15), result.patchDate());
            assertEquals(PatchDateResolver.Source.PUBLICATION_DATE_PROXY, result.source());
            assertNull(result.appliedAt());
        }
    }

    @Test
    void naturalTimestampAndVersionSubjectResolveWithoutPublisherZone() {
        for (String body : List.of("The update was released on January 23, 2025 at 13:00 (KST).",
                "The Steam version has been updated on 2025.01.23 13:00 (KST).")) {
            var result = resolve(body, null);
            assertEquals(PatchDateResolver.Status.RESOLVED, result.status(), body);
            assertEquals(Instant.parse("2025-01-23T04:00:00Z"), result.appliedAt());
        }
    }

    @Test
    void maintenanceDateAndCompletedChangesShareAnExplicitJstDay() {
        String body = "The maintenance details for 01/07/2026 JST are as follows.\n"
                + "The season has begun.\nFixed a crash.\nAdded a new character.\n"
                + "Balance adjustments are scheduled for next week.";
        var result = PatchDateResolver.resolve(confirmed(PatchClassifier.Scope.DEFAULT),
                "Maintenance Notice (2026/01/07)", body, PUBLISHED_AT, null);
        assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertEquals(LocalDate.of(2026, 1, 7), result.patchDate());
        assertNull(result.appliedAt());
    }

    @Test
    void plannedChangesAndUnrelatedEventDatesNeverBecomePublicationEstimates() {
        for (String body : List.of("We will release this patch tomorrow.\nFixed a crash.\nAdded a map.",
                "Scheduled maintenance ends at 12:00 UTC.\nFixed a crash.\nAdded a map.",
                "This patch has not been released.\nFixed a crash.\nAdded a map.")) {
            var result = resolve(body, null);
            assertNotEquals(PatchDateResolver.Status.ESTIMATED, result.status());
            assertNull(result.patchDate());
        }
        var result = resolve("This patch is now live!\nThe event starts on 2026-09-14 at 10:00 KST.", null);
        assertNotEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertNull(result.appliedAt());
    }

    @Test
    void invalidClockAndConflictingTimezonesCannotFallBackToDateOnly() {
        for (String text : List.of("2026-09-14 25:00 KST", "2026-09-14 12:00 KST UTC",
                "2026-09-14 12:60 KST")) {
            assertReview(resolve("This patch was released on " + text + ".", null), "DATE_OR_TIMEZONE_UNRESOLVED");
        }
    }

    @Test
    void completedServerFixWithAbbreviatedMonthUsesActualClassifier() {
        String title = "Notice: Jan. 23rd 2025 (KST) Scheduled Update Server Error Fixed";
        String body = "We have identified a server error and have deployed the fix.\n"
                + "[h2]Solution[/h2]The error has been fixed on Jan. 23rd, 2025 13:00 (KST).";
        var classification = PatchClassifier.classify(title, body, List.of());
        var result = PatchDateResolver.resolve(classification, title, body, PUBLISHED_AT, null);
        assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertEquals(Instant.parse("2025-01-23T04:00:00Z"), result.appliedAt());
    }

    @Test
    void mobileAnnouncementCanContainAnExplicitCompletedSteamDeployment() {
        String title = "Notice: Version 1.36.2 Deployment on Mobile Platforms";
        String body = "The fix will be deployed on each app store on Feb. 2nd, 2024 at 12:00 (KST).\n"
                + "- iOS, Android: 2024.02.02 12:00 (KST)\n"
                + "- Steam version of the game was updated on 2024.02.02 (Fri) 01:30 (KST)";
        var classification = PatchClassifier.classify(title, body, List.of());
        var result = PatchDateResolver.resolve(classification, title, body, PUBLISHED_AT, null);
        assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertEquals(Instant.parse("2024-02-01T16:30:00Z"), result.appliedAt());
        assertFalse(result.evidence().contains("12:00"));
    }

    @Test
    void mobileAnnouncementCannotUseAnAmbiguousOrPendingSteamLine() {
        for (String line : List.of("Steam version will be updated on 2024.02.02 01:30 KST",
                "Steam version has been updated on 2024.02.02", "The mobile update was deployed at 2024-02-02T01:30:00Z")) {
            var classification = PatchClassifier.classify("Mobile deployment", line, List.of());
            var result = PatchDateResolver.resolve(classification, "Mobile deployment", line, PUBLISHED_AT, null);
            assertEquals(PatchDateResolver.Status.REVIEW_REQUIRED, result.status());
            assertNull(result.patchDate());
        }
    }

    @Test
    void yearShapedVersionsAndHistoricalDatesAreNotCurrentDeploymentDates() {
        assertReview(resolve("VRChat 2021.2.4 update is now live!"), "DATE_OR_TIMEZONE_UNRESOLVED");
        assertReview(resolve("The previous update was released on January 1, 2025 at 10:00 KST."),
                "DEPLOYMENT_CONTEXT_REQUIRES_VERIFICATION");
    }

    @Test
    void maintenanceDateWithOnlyAChangeListIsNotProofOfCompletion() {
        String body = "The maintenance details for 2026/09/15 JST are as follows.\nFixed a crash.\nAdded a map.";
        var result = PatchDateResolver.resolve(confirmed(PatchClassifier.Scope.DEFAULT),
                "Maintenance Notice (2026/09/15)", body, PUBLISHED_AT, null);
        assertEquals(PatchDateResolver.Status.REVIEW_REQUIRED, result.status());
        assertNull(result.patchDate());
    }

    @Test
    void alphaAndMaintenanceSchedulesCannotUsePublicationProxy() {
        var confirmed = confirmed(PatchClassifier.Scope.DEFAULT);
        var alpha = PatchDateResolver.resolve(confirmed, "Closed Alpha 4 Patch",
                "Added stability improvements.\nFixed an opacity issue.", PUBLISHED_AT, null);
        var schedule = PatchDateResolver.resolve(confirmed, "Patch Notes",
                "Maintenance Period: September 15, 2026 23:00 PDT\nAdded a map.\nFixed a crash.", PUBLISHED_AT, null);
        assertEquals(PatchDateResolver.Status.REVIEW_REQUIRED, alpha.status());
        assertEquals(PatchDateResolver.Status.REVIEW_REQUIRED, schedule.status());
    }

    @Test
    void invalidAmbiguousAndIncompleteDatesStayUnresolved() {
        for (String date : List.of("2026-02-29", "2026-13-01", "September 31, 2026", "09/14/2026",
                "September 14", "2026-09-14T12:00:00", "next Monday", "2026-09-14 at 8 PM PST")) {
            assertReview(resolve("This patch was released on " + date + "."),
                    "DATE_OR_TIMEZONE_UNRESOLVED");
        }
        assertEquals(LocalDate.of(2024, 2, 29), resolve("This patch was released on 2024-02-29.").patchDate());
    }

    @Test
    void dateAfterPublicationIsNotConfirmedEvenWithCompletionWording() {
        assertReview(resolve("This patch was released on 2026-09-16."), "DEPLOYMENT_AFTER_PUBLICATION");
        assertReview(resolve("This patch was released at 2026-09-15T01:00:01Z."), "DEPLOYMENT_AFTER_PUBLICATION");
    }

    @Test
    void conflictingDaysAndDistinctTimesOnSameDayRequireReview() {
        assertReview(resolve("This patch was released on 2026-09-13.\nThe update went live on 2026-09-14."),
                "CONFLICTING_DEPLOYMENT_DATES");
        assertReview(resolve("This patch was deployed at 2026-09-14T16:00:00Z.\n"
                + "This patch was deployed at 2026-09-14T17:00:00Z."), "CONFLICTING_DEPLOYMENT_DATES");
    }

    @Test
    void consistentDateAndTimestampPreferTimestampInEitherOrder() {
        String date = "This patch was released on 2026-09-15.";
        String timestamp = "The update went live at 2026-09-14T16:00:00Z.";
        for (String body : List.of(date + "\n" + timestamp, timestamp + "\n" + date)) {
            var result = resolve(body);

            assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
            assertEquals(Instant.parse("2026-09-14T16:00:00Z"), result.appliedAt());
            assertTrue(result.evidence().contains(date));
            assertTrue(result.evidence().contains(timestamp));
        }
    }

    @Test
    void equivalentTimestampsAndRepeatedEvidenceAreConsistent() {
        String sentence = "This patch was deployed at 2026-09-14T16:00:00Z.";
        var result = resolve(sentence + "\n" + sentence
                + "\nThis patch was deployed at 2026-09-15T01:00:00+09:00.");

        assertEquals(PatchDateResolver.Status.RESOLVED, result.status());
        assertEquals(2, result.evidence().lines().count());
    }

    @Test
    void unrelatedDlcDateDoesNotOverrideCurrentPatchDate() {
        var result = resolve("This patch was released on 2026-09-14.\nThe DLC will launch on 2026-10-20.");

        assertEquals(LocalDate.of(2026, 9, 14), result.patchDate());
    }

    @Test
    void platformSuffixCannotBeSilentlyDiscarded() {
        assertReview(resolve("This patch was released on 2026-09-14 for consoles only."),
                "DATE_OR_TIMEZONE_UNRESOLVED");
    }

    @Test
    void latePendingDeploymentBlocksEarlierCompletedStatement() {
        String body = "This patch was released on 2026-09-14.\n" + "Fixed a crash.\n".repeat(150)
                + "This patch will not go live until tomorrow.";
        // Test independently of whether the classifier already detects the contradiction.
        assertReview(PatchDateResolver.resolve(confirmed(PatchClassifier.Scope.DEFAULT), "Patch Notes", body,
                PUBLISHED_AT, TimeRule.ZONE), "DEPLOYMENT_PENDING_OR_CONFLICTING");
    }

    @Test
    void sectionContextPreventsHistoricalAndTestDatesBeingCurrent() {
        for (String heading : List.of("Previous update", "Roadmap", "Beta branch", "Console release")) {
            String body = "[h2]" + heading + "[/h2]This patch was released on 2026-09-14.";
            assertReview(resolve(body), "DEPLOYMENT_CONTEXT_REQUIRES_VERIFICATION");
        }
    }

    @Test
    void formattedDeploymentSentenceRetainsReadableEvidence() {
        var result = resolve("[h2]Release[/h2]<p>This patch was released on <b>2026-09-14</b>.</p>");

        assertEquals(LocalDate.of(2026, 9, 14), result.patchDate());
        assertTrue(result.evidence().contains("2026-09-14"));
    }

    @Test
    void plainTextHistoricalContextRequiresReview() {
        assertReview(resolve("Previous update:\n\nThis patch was released on 2026-09-14."),
                "DEPLOYMENT_CONTEXT_REQUIRES_VERIFICATION");
    }

    @Test
    void titleCanSupplyCompletedDeploymentEvidence() {
        String title = "This patch was released on 2026-09-14.";
        String body = "Fixed a crash.";
        var classification = PatchClassifier.classify(title, body, List.of("patchnotes"));
        var result = PatchDateResolver.resolve(classification, title, body, PUBLISHED_AT, TimeRule.ZONE);

        assertEquals(LocalDate.of(2026, 9, 14), result.patchDate());
        assertEquals(title, result.evidence());
    }

    @Test
    void nonDefaultScopesAndUnconfirmedClassificationCannotResolveDate() {
        String body = "This patch was released on 2026-09-14.";
        for (PatchClassifier.Scope scope : PatchClassifier.Scope.values()) {
            if (scope == PatchClassifier.Scope.DEFAULT) continue;
            assertReview(PatchDateResolver.resolve(confirmed(scope), "Patch Notes", body,
                    PUBLISHED_AT, TimeRule.ZONE), "DEPLOYMENT_SCOPE_REQUIRES_VERIFICATION");
        }
        var unconfirmed = new PatchClassifier.Result(PatchClassifier.Decision.REVIEW_REQUIRED,
                PatchClassifier.Scope.DEFAULT, 0, "UNKNOWN", "");
        assertReview(PatchDateResolver.resolve(unconfirmed, "Patch Notes", body, PUBLISHED_AT, TimeRule.ZONE),
                "PATCH_NOT_CONFIRMED");
    }

    @Test
    void nonPatchAndMissingInputsHaveExplicitResults() {
        var nonPatch = new PatchClassifier.Result(PatchClassifier.Decision.NOT_PATCH,
                PatchClassifier.Scope.DEFAULT, 2, "ANNOUNCEMENT_OR_PREVIEW", "Patch Preview");
        assertEquals(PatchDateResolver.Status.NOT_APPLICABLE,
                PatchDateResolver.resolve(nonPatch, null, null, null, null).status());
        assertReview(PatchDateResolver.resolve(confirmed(PatchClassifier.Scope.DEFAULT), "Patch", null,
                PUBLISHED_AT, TimeRule.ZONE), "MISSING_TITLE_OR_BODY");
        assertThrows(NullPointerException.class, () -> PatchDateResolver.resolve(null, "Patch", "body", null, null));
    }

    private static PatchDateResolver.Result resolve(String body) {
        return resolve(body, TimeRule.ZONE);
    }

    private static PatchDateResolver.Result resolve(String body, ZoneId zone) {
        return resolve(body, PUBLISHED_AT, zone);
    }

    private static PatchDateResolver.Result resolve(String body, Instant publishedAt, ZoneId zone) {
        var classification = PatchClassifier.classify("Patch Notes", body, List.of("patchnotes"));
        return PatchDateResolver.resolve(classification, "Patch Notes", body, publishedAt, zone);
    }

    private static PatchClassifier.Result confirmed(PatchClassifier.Scope scope) {
        return new PatchClassifier.Result(PatchClassifier.Decision.PATCH, scope, 1, "EXACT_PATCH_TAG", "patchnotes");
    }

    private static void assertReview(PatchDateResolver.Result result, String reason) {
        assertEquals(PatchDateResolver.Status.REVIEW_REQUIRED, result.status());
        assertEquals(reason, result.reason());
        assertNull(result.patchDate());
        assertNull(result.appliedAt());
        assertEquals(PatchDateResolver.Source.NONE, result.source());
    }
}
