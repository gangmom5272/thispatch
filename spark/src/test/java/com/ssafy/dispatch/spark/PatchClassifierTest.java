package com.ssafy.dispatch.spark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.ssafy.dispatch.spark.PatchClassifier.Decision.*;

class PatchClassifierTest {
    @Test void anotherGamesCollaborationDoesNotBecomeSourceGamesPatch() {
        var result = PatchClassifier.classify("Dead Cells", "Astral Ascent: The Dead Cells Rendezvous Update",
                "The Dead Cells update for Astral Ascent is now live!", List.of("patchnotes"));
        assertEquals(REVIEW_REQUIRED, result.decision());
        assertEquals(PatchClassifier.Scope.OTHER_GAME, result.scope());
        assertTrue(result.evidence().contains("Astral Ascent"));
    }

    @Test void explicitMatchingGameRemainsPatch() {
        assertEquals(PATCH, PatchClassifier.classify("Astral Ascent", "Collaboration Update",
                "The Dead Cells update for Astral Ascent is now live!", List.of()).decision());
    }

    @Test void missingGameIdentityCannotConfirmExplicitTarget() {
        assertEquals(REVIEW_REQUIRED, classify("Collaboration Update",
                "The Dead Cells update for Astral Ascent is now live!").decision());
    }

    @Test void unrelatedStoreLinkDoesNotRejectSourceGamePatch() {
        assertEquals(PATCH, PatchClassifier.classify("Dead Cells", "Patch 1.2",
                "Fixed a crash. Also check out https://store.steampowered.com/app/1280930/Astral_Ascent/",
                List.of()).decision());
    }

    @Test void mobileOnlyTitleRequiresPlatformVerificationEvenWithTag() {
        var result = classify("Dead Cells Mobile Update - Clean Cut & The End is Near",
                "These updates are now available for Dead Cells Mobile!", "patchnotes");
        assertEquals(REVIEW_REQUIRED, result.decision());
        assertEquals(PatchClassifier.Scope.NON_STEAM, result.scope());
    }

    @Test void mobileMentionDoesNotRejectExplicitPcRelease() {
        assertEquals(PATCH, classify("Terraria - Out Now for PC! (Console/Mobile Soon)",
                "Patch Notes\nAdded new enemies.\nFixed a crash.").decision());
    }

    @Test void experimentalAndUnstableAreTestBranches() {
        for (String title : List.of("1.2 Experimental Hotfix", "Build 42.19.0 Unstable Released")) {
            var result = classify(title, "Patch Notes\nAdded new equipment.\nFixed a crash.");
            assertEquals(PATCH, result.decision());
            assertEquals(PatchClassifier.Scope.TEST, result.scope());
        }
    }

    @Test void stableAndUnstableInOneTitleAreMixedNotTestOnly() {
        var result = classify("42.20 STABLE & 42.19 UNSTABLE & LEGACY Hotfixes Released", "Fixed a crash.");
        assertEquals(PATCH, result.decision());
        assertEquals(PatchClassifier.Scope.MIXED, result.scope());
    }

    @Test void unstableWordDoesNotMatchStableSubstring() {
        assertEquals(PatchClassifier.Scope.TEST, classify("Unstable Hotfix", "Fixed a crash.").scope());
    }

    @Test void unstableObjectInBodyDoesNotImplyTestBranch() {
        assertEquals(PatchClassifier.Scope.DEFAULT, classify("Patch 1.2",
                "Fixed unstable physics and experimental weapon damage.").scope());
    }

    @Test void lateFutureLiveAndAvailableTestNeedSeparateDeployment() {
        var result = classify("Free Weekend and Patch News!", "Hello players.\n".repeat(150)
                + "These patch notes will not go live until next week.\nFixed a crash.\n"
                + "We've made it available on a test branch for you to try!", "patchnotes");
        assertEquals(REVIEW_REQUIRED, result.decision());
        assertEquals(PatchClassifier.Scope.MIXED, result.scope());
        assertEquals("TEST_AVAILABLE_LIVE_DEPLOYMENT_PENDING", result.reason());
        assertTrue(result.evidence().contains("next week"));
        assertTrue(result.evidence().contains("test branch"));
    }

    @Test void lateExplicitDeferralWithoutTestAlsoNeedsReview() {
        var result = classify("Patch Notes", "Greetings.\n".repeat(200)
                + "This patch won't go live until next week.\nFixed a crash.");
        assertEquals(REVIEW_REQUIRED, result.decision());
    }

    @Test void alreadyFixedClientSecurityIssueIsNotServerCompletion() {
        var result = classify("V Rising Security Update",
                "We've already patched the issue on clients and are currently working to patch it on the server client as well.");
        assertEquals(PATCH, result.decision());
        assertEquals(PatchClassifier.Scope.CLIENT, result.scope());
        assertEquals("COMPLETED_CLIENT_SECURITY_FIX", result.reason());
        assertTrue(result.evidence().contains("already patched the issue on clients"));
        assertEquals(PatchClassifier.Scope.CLIENT, classify("Security Update",
                "The update is now live. We've already patched the issue on clients.").scope());
    }

    @Test void proposedSecurityFixDoesNotBecomeCompletedPatch() {
        assertEquals(REVIEW_REQUIRED, classify("Security Update",
                "We will patch the issue on clients next week.").decision());
    }

    @Test void negatedSecurityFixDoesNotBecomeCompletedPatch() {
        assertEquals(REVIEW_REQUIRED, classify("Security Update",
                "We have not yet patched the issue on clients.").decision());
    }

    @Test void availableTestCanBeFoundAtEndWithoutFutureLiveAnnouncement() {
        var result = classify("Patch 1.2", "Hello.\n".repeat(250)
                + "The patch is available on a test branch.");
        assertEquals(PATCH, result.decision());
        assertEquals(PatchClassifier.Scope.TEST, result.scope());
    }

    private static PatchClassifier.Result classify(String title, String body, String... tags) {
        return PatchClassifier.classify(title, body, List.of(tags));
    }

    @Test void tagProvidesPositiveEvidence() {
        var result = classify("Maintenance", "Stability improvements.", "patchnotes");
        assertEquals(PATCH, result.decision());
        assertEquals(1, result.stage());
    }

    @Test void tagsUseExactMatchNotSubstring() {
        assertEquals(REVIEW_REQUIRED, classify("Information", "More news soon.", "not_patchnotes").decision());
    }

    @Test void taggedNonMainGameUpdateNeedsReview() {
        var result = classify("Release Note", "The main game is not impacted by the update.", "patchnotes");
        assertEquals(REVIEW_REQUIRED, result.decision());
        assertEquals("TAG_CONFLICT_MAIN_GAME_UNAFFECTED", result.reason());
    }

    @Test void plannedBetaIsNotAnAppliedPatch() {
        var result = classify("Resurgence enters the MW4 Beta this Friday", "Preload tomorrow. Open Beta starts soon.");
        assertEquals(NOT_PATCH, result.decision());
        assertEquals(PatchClassifier.Scope.TEST, result.scope());
    }

    @Test void publicTestPatchIsRetainedWithSeparateScope() {
        var result = classify("Patch 0.221.13 (Public Test)", "Fixed a crash.", "patchnotes");
        assertEquals(PATCH, result.decision());
        assertEquals(PatchClassifier.Scope.TEST, result.scope());
    }

    @Test void gameplayPreviewDoesNotMeanAnnouncement() {
        assertEquals(PATCH, classify("Counter-Strike 2 Update",
                "The bomb damage health preview is now revealed when the bomb becomes audible.", "patchnotes").decision());
    }

    @Test void previewTitleOverridesEvenAConflictingTag() {
        assertEquals(REVIEW_REQUIRED, classify("Patch Preview", "Fixed a crash in the upcoming build.", "patchnotes").decision());
        assertEquals(NOT_PATCH, classify("Patch Preview", "A look at tomorrow's update.").decision());
    }

    @Test void dlcScheduleDoesNotCancelAlreadyReleasedPatch() {
        assertEquals(PATCH, classify("Patch Notes Version 1.17",
                "Patch 1.17 has been released. This patch supports the DLC scheduled for release tomorrow.").decision());
    }

    @Test void communityUpdateRequiresActualChanges() {
        assertEquals(NOT_PATCH, classify("Community Update #36", "Celebrating community mods.").decision());
        assertEquals(PATCH, classify("Community Update #34 & Hotfix #32", "Hotfix #32 is going live today.").decision());
    }

    @Test void launchWithChangesCanBeAnExistingGameUpdate() {
        var result = classify("Valheim 1.0 Has Arrived!", "Patch Notes\nAdded new enemies.\nFixed a crash.");
        assertEquals(PATCH, result.decision());
        assertEquals(3, result.stage());
        assertEquals(REVIEW_REQUIRED, classify("New game launches on Steam", "Play now!").decision());
    }

    @Test void genericTitleUsesStructuredChangeEvidence() {
        var result = classify("Devoid of Liberty: 7.0.0", "Bug Fixes\nFixed broken missions.\nAdded new equipment.");
        assertEquals(PATCH, result.decision());
        assertEquals(5, result.stage());
    }

    @Test void formattingPreservesChangeList() {
        assertEquals(PATCH, classify("Build 123", "[h2]Bug Fixes[/h2][list][*]Fixed broken missions.[*]Added equipment.[/list]").decision());
    }

    @Test void decoratedHeadingsStillIdentifyChangeLists() {
        var result = classify("Devoid of Liberty: 7.0.0",
                "📍 Patch Highlights\nAdded new equipment.\nFixed broken missions.");
        assertEquals(PATCH, result.decision());
        assertEquals(5, result.stage());
    }

    @Test void officialReleaseChangelogIsNotJustALaunchAdvertisement() {
        var result = classify("Palworld v1.0 - Official Release Changelog",
                "Palworld 1.0 - Changelog\nAdded new enemies.\nFixed broken missions.");
        assertEquals(PATCH, result.decision());
        assertEquals(3, result.stage());
    }

    @Test void vagueSingleChangeIsNotEnough() {
        assertEquals(REVIEW_REQUIRED, classify("Hello", "We fixed our registration form.").decision());
    }

    @Test void normalPatchMayMentionOtherPlatformFutureRelease() {
        assertEquals(PATCH, classify("Hotfix 1.0", "We have hotfixes ready for you today.\nThe patch will arrive on consoles next week.", "patchnotes").decision());
    }

    @Test void missingBodyIsNotReportedAsNotPatch() {
        assertEquals(REVIEW_REQUIRED, classify("Patch 2.0", "", "patchnotes").decision());
    }

    @Test void gameNameAndVersionBeforeChangelogAreAccepted() {
        var result = classify("Terraria 1.4.5.7 - Out Now for PC! (Console/Mobile Soon)",
                "TERRARIA 1.4.5.7 CHANGELOG\u200b\nI. Content Changes & Additions\nItems\n"
                        + "- Added Daybloom Staff.\n- Added Glacier Fang.");
        assertEquals(PATCH, result.decision());
        assertTrue(result.evidence().contains("CHANGELOG"));
        assertTrue(result.evidence().contains("Added Daybloom Staff"));
    }

    private static String namedFeatures() {
        return "Star Map - Explore a new star map to discover missions and points of interest.\n"
                + "Hulks - Strip and salvage huge space hulks using your ship's new tractor beam.";
    }

    @Test void introductionConnectsNamedFeatureDescriptions() {
        var result = classify("A complete overhaul to space arrives in COSMOS (7.0)",
                "We’ve added so much to space in this update, here’s just a sampler of what’s new…\n"
                        + namedFeatures());
        assertEquals(PATCH, result.decision());
        assertTrue(result.evidence().contains("We’ve added"));
        assertTrue(result.evidence().contains("Star Map"));
        assertTrue(result.evidence().contains("Hulks"));
    }

    @Test void narrativeIntroductionSupportsAsciiApostropheAndHtml() {
        assertEquals(PATCH, classify("COSMOS", "<p>We've added new systems in this update.</p>"
                + "<p><b>Star Map</b> - Discover new missions and locations on the star map.</p>"
                + "<p><b>Hulks</b> - Salvage wrecks with a new tractor beam on your ship.</p>").decision());
    }

    @Test void existingPatchCanContainMerchandiseAfterItsChangeSection() {
        assertEquals(PATCH, classify("COSMOS", "This update introduces new space systems.\n"
                + namedFeatures() + "\nMerchandise\nBuy our new plushies.").decision());
    }

    @Test void merchandiseSectionCannotSupplyMissingChanges() {
        assertEquals(REVIEW_REQUIRED, classify("Hello", "This update includes improvements.\n"
                + "Merchandise\n" + namedFeatures()).decision());
    }

    @Test void changelogLinkWithoutChangesIsNotEnough() {
        assertEquals(REVIEW_REQUIRED, classify("Hello", "TERRARIA 1.4.5.7 CHANGELOG\n"
                + "See the linked page for details.\nMerchandise\nAdded a new shirt.\nAdded a new mug.").decision());
    }

    @Test void changesBeforeAChangelogHeadingDoNotSupportThatHeading() {
        assertEquals(REVIEW_REQUIRED, classify("Hello", "Added new shirts.\nAdded new mugs.\n"
                + "TERRARIA 1.4.5.7 CHANGELOG\nSee the linked page.").decision());
    }

    @Test void versionBetweenPatchAndPreviewDoesNotHidePreview() {
        assertEquals(NOT_PATCH, classify("Patch 1.2 Preview", "Fixed a crash.\n".repeat(15)).decision());
        assertEquals(REVIEW_REQUIRED, classify("Patch 1.2 Preview", "Fixed a crash.\n".repeat(15), "patchnotes").decision());
    }

    @Test void cancelledPatchDoesNotBecomeAnAppliedCase() {
        assertEquals(NOT_PATCH, classify("Patch 1.2 Cancelled", "We will not deploy this patch.").decision());
    }

    @Test void developmentContextStopsNarrativeEvidence() {
        assertEquals(REVIEW_REQUIRED, classify("A look ahead", "This update is still in development.\n"
                + "We have added new systems in this update.\n" + namedFeatures()).decision());
    }

    @Test void monthlyRecapDoesNotCreateAnotherPatchCase() {
        assertEquals(NOT_PATCH, classify("Terraria State of the Game - August", "Released last week.\n"
                + "TERRARIA 1.4.5.7 CHANGELOG\nAdded a new staff.\nFixed a crash.").decision());
    }

    @Test void narrativeEvidenceCanAppearAfterLongIntroduction() {
        assertEquals(PATCH, classify("COSMOS", "Greetings to all our players.\n".repeat(70)
                + "This update introduces new space systems.\n" + namedFeatures()).decision());
    }

    @Test void longReleaseNotesRecognizeTheirLaterFixesSubsection() {
        assertEquals(PATCH, classify("Valheim 1.0 Has Arrived!", "Patch Notes\n"
                + "* Weapon: A new sword\n".repeat(70)
                + "Fixes & Improvements\n* Added build author display.\n* Added controller support.").decision());
    }
}
