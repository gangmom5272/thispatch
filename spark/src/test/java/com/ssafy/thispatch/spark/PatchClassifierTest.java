package com.ssafy.thispatch.spark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.ssafy.thispatch.spark.PatchClassifier.Decision.*;

class PatchClassifierTest {
    @Test void sharedOutputDistinguishesClassifiedReasonsFromUnjudged() {
        var patch = classify("Hotfix", "Fixed a crash.");
        var preview = classify("Patch Preview", "Next week.");
        var uncertain = classify("Patch Preview", "Next week.", "patchnotes");
        var missing = classify("Patch Notes", null);
        assertTrue(patch.isPatch());
        assertFalse(preview.isPatch());
        assertFalse(uncertain.isPatch());
        assertFalse(missing.isPatch());
        assertNotEquals(preview.reason(), uncertain.reason());
        for (var result : List.of(patch, preview, uncertain, missing)) {
            assertFalse(result.reason().isBlank());
            assertNotEquals(PatchClassifier.UNJUDGED_REASON, result.reason());
        }
    }

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

    @Test void bracketedUpdateTitleIsContentNotAnUnderlineTag() {
        assertEquals("[Update v0.9.7]", PatchClassifier.plainText("[Update v0.9.7]").strip());
        var result = classify("[Update v0.9.7]",
                "[list][*][p]Fixed untranslated dialogue.[/p][/*]"
                + "[*][p]Added cursor adjustment.[/p][/*][/list]", "patchnotes");
        assertEquals(PATCH, result.decision());
        assertEquals("PATCHNOTES_TAG", result.reason());
    }

    @Test void onlyActualFormattingTagsAreRemoved() {
        assertEquals("[Update] [Patch] [Items] [Balance] [Preview]",
                PatchClassifier.plainText("[Update] [Patch] [Items] [Balance] [Preview]").strip());
        assertEquals("text", PatchClassifier.plainText(
                "[b][url=\"https://example.com\"]text[/url][/b]").strip());
        assertTrue(PatchClassifier.plainText("[img src=\"image.png\"]caption[/img]").isBlank());
    }

    @Test void hereAloneDoesNotConfirmDeployment() {
        assertEquals(REVIEW_REQUIRED, classify("Update 17.4.0 is here!",
                "Enjoy the celebration! Visit our website to find out more.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Our new update",
                "The update is here! We hope you like it.").decision());
        assertEquals(NOT_PATCH, classify("Our soundtrack is here!",
                "Listen to your favourite music.").decision());
    }

    @Test void hereDoesNotOverrideAnExplicitPreview() {
        assertEquals(NOT_PATCH, classify("Update 1.2 Preview is here!",
                "Fixed a crash.\nAdded equipment.").decision());
    }

    @Test void versionTitleWithTwoShortFixesIsPatch() {
        assertEquals(PATCH, classify("1.7.5.3",
                "[h1]GENERAL[/h1]\n- fixed only helmet drops\n- fixed a bug in the anti-cheat").decision());
        assertEquals(REVIEW_REQUIRED, classify("1.7.5.3", "See you next week.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Our roadmap 1.7.5.3",
                "Fixed a crash.\nAdded equipment.").decision());
    }

    @Test void bracketedChangeLabelsAreRecognized() {
        assertEquals(PATCH, classify("Update 0.0.98.1",
                "[Added] New course options.\n[Fixed] Ball collision.").decision());
    }

    @Test void descriptiveBulletsUnderChangeCategoriesAreRecognized() {
        assertEquals(PATCH, classify("3.21.8.0 Update",
                "[h2]Added:[/h2][list][*]New vehicle and engine options."
                + "[*]Additional spawn settings.[/list]").decision());
        assertEquals(PATCH, classify("1.12",
                "[h2]Fixed[/h2][list][*]Objects clipping through walls."
                + "[*]Save files failing to load.[/list]").decision());
    }

    @Test void categoryHeadingsDoNotTurnUnrelatedProseIntoChanges() {
        assertEquals(REVIEW_REQUIRED, classify("Update 1.2",
                "Added\nVisit our website for more details.\nThanks for your support.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update 1.2",
                "Added\nMerchandise\n- A new shirt for sale.\n- A new mug for sale.").decision());
    }

    @Test void componentPrefixedAndPassiveChangesAreRecognized() {
        assertEquals(PATCH, classify("DFHack 51.11-r1",
                "Changelog\nFixes\n- gui/petitions: fix date math when determining age.\n"
                + "- gui/rename: fix commandline processing.").decision());
        assertEquals(PATCH, classify("Update 1.6.1",
                "- A new agent was added to the roster.\n- Steering has been improved.").decision());
    }

    @Test void oneFixIsEnoughWithAnExplicitLiveHotfixStatement() {
        assertEquals(PATCH, classify("PAYDAY 2: Update 97.5",
                "We're live with a hotfix.\nHotfix 97.5 changelog\nLevels\n"
                + "- Fixed an issue where the escape van doors would not open.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update 97.5",
                "We're live with a stream.\n- Fixed an issue in our video captions.").decision());
    }

    @Test void scheduledNotesWithCompletedVerbListsAreStillFuture() {
        assertEquals(NOT_PATCH, classify("2025.07.17 Scheduled Update Additional Notice",
                "Bug Fixes\n- Fixed broken missions.\n- Added equipment.").decision());
        assertEquals(NOT_PATCH, classify("5.7 정기점검 사전 안내",
                "Added\n- New vehicle settings.\n- New equipment options.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Scheduled Update",
                "Fixed broken missions.\nAdded equipment.", "patchnotes").decision());
    }

    @Test void futureChangesDoNotSupplyVersionEvidence() {
        assertEquals(REVIEW_REQUIRED, classify("1.7.5.3",
                "- We will add equipment.\n- A new agent will be added.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update 1.2",
                "Added\n- New maps coming soon.\n- New vehicles coming soon.").decision());
    }


    @Test void scheduledVersionCannotUseItsPlannedChangelogAsDeploymentEvidence() {
        assertEquals(NOT_PATCH, classify("1.7.5.3",
                "This version is planned for next week.\nFixed a crash.\nAdded equipment.").decision());
    }

    @Test void conditionalFeatureDescriptionsAreNotCompletedChanges() {
        assertEquals(REVIEW_REQUIRED, classify("Update guide",
                "If an item was added, check its price.\nWhen damage was increased, reload your save.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update guide",
                "An item is added whenever you win.\nDamage is increased whenever you level up.").decision());
    }


    @Test void changeCategoryRecognizesVerbAtEndWithoutTreatingAvailabilityAsEvidence() {
        assertEquals(PATCH, classify("Update 1.6.1",
                "The PC update is available.\nFixes:\n"
                + "Issue with cars missing wheels fixed\nIssue with the class filter fixed").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update 1.6.1",
                "The PC update is available.").decision());
    }

    @Test void futureFlashUpdateIsExcludedEvenWithNumberedFixes() {
        assertEquals(NOT_PATCH, classify("Flash Update on September 2",
                "Heartopia will undergo a flash update from 12:00 to 14:00.\nBug Fixes\n"
                + "1. Fixed flooring display.\n2. Fixed roof placement.").decision());
    }


    @Test void futureEventMentionDoesNotUndoCompletedChanges() {
        assertEquals(PATCH, classify("Update 45",
                "Improved visibility for upcoming events.\nIncreased recovery duration to 20 minutes.").decision());
    }

    @Test void scopeLabelsDoNotHideCompletedFixes() {
        assertEquals(PATCH, classify("Teams Mode Out Now",
                "Patch Notes\n- [PC] Fixed mouse input.\n- [Level Editor] Fixed asset collision.").decision());
        assertEquals(REVIEW_REQUIRED, classify("Update 1.2",
                "- [Upcoming] Fixed mouse input.\n- [Preview] Fixed asset collision.").decision());
    }

    @Test void changesComingWithNextSeasonAreNotCompleted() {
        assertEquals(NOT_PATCH, classify("Nov 24 Update",
                "Read what changes are coming with the new season on Nov 24!\n"
                + "Fixes\n- Fixed input handling.\n- Fixed movement issues.").decision());
    }


    @Test void longIntroductionCannotHideSeasonDeploymentPreview() {
        assertEquals(NOT_PATCH, classify("Nov 24 Update",
                "Here is our reasoning for the balance changes.\n".repeat(40)
                + "Read what changes are coming with the new season on Nov 24!\n"
                + "Fixes\n- Fixed input handling.\n- Fixed movement issues.").decision());
    }

    @Test void laterPlansDoNotCancelAlreadyListedChanges() {
        assertEquals(PATCH, classify("Update 45",
                "Fixed input handling.\nAdded equipment.\n"
                + "The next update is planned for next week.").decision());
    }

}
