package com.ssafy.dispatch.spark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real-notice failures from the five-notice audit, plus nearby negative controls. */
class PatchChangeRegressionTest {
    @Test void questPrefixAndExplanationStayInEvidence() {
        String text = "Freedom - Fixed the journal entry that appears when the player chooses not to steal a vehicle.";
        var result = PatchChangeExtractor.extract(text);
        assertEquals(1, result.size());
        assertEquals("fix", result.get(0).changeTypeCode());
        assertEquals(text, result.get(0).evidenceQuote());
        assertEquals("unknown", result.get(0).targetTypeCode());
    }

    @Test void currentAdditionCanDescribeAPastRemoval() {
        String text = "Added Guide To Old Style Parkour, which restores some player movement techniques removed in 1.4.5.";
        assertEquals("add", PatchChangeExtractor.extract(text).get(0).changeTypeCode());
    }

    @Test void laterSentencesArePreservedInsteadOfDiscarded() {
        String text = "Updated AutoDrive. When driving to a selected point, the vehicle now overtakes blocking vehicles. Free Roam has also been upgraded.";
        assertEquals(text, PatchChangeExtractor.extract(text).get(0).evidenceQuote());
    }

    @Test void continuationKeepsOriginalWhitespace() {
        String text = "Added a toggle.\r\n\r\n  It can be found in Settings → Graphics.";
        assertEquals(text, PatchChangeExtractor.extract(text).get(0).evidenceQuote());
    }

    @Test void abbreviationInParenthesesIsNotASentenceBoundary() {
        String text = "Fixed certain enemies being able to spawn on platforms when they shouldn't (e.g. Angry Dandelion).";
        assertEquals(text, PatchChangeExtractor.extract(text).get(0).evidenceQuote());
    }

    @Test void quotedActionDoesNotCreateAnotherChange() {
        String text = "Fixed the label \"Added a weapon. Fixed a skill\" in the UI.";
        assertEquals(1, PatchChangeExtractor.extract(text).size());
        assertEquals(text, PatchChangeExtractor.extract(text).get(0).evidenceQuote());
    }

    @Test void pronounDependentOperationStaysWithItsAntecedent() {
        String text = "Fixed Heroicis' Set not being produced, and fixed it overriding Shimmer behaviour.";
        assertEquals(1, PatchChangeExtractor.extract(text).size());
        assertEquals(text, PatchChangeExtractor.extract(text).get(0).evidenceQuote());
    }

    @Test void indirectPlayerAndEnemyMentionsAreNotDirectTargets() {
        var result = PatchChangeExtractor.extract("Reworked to scale with the player's movement stats.\n"
                + "Adjusted Void shader to improve visibility of the player's attacks.\n"
                + "Improved all whips enemy collision detection.");
        assertEquals("unknown", result.get(0).targetTypeCode());
        assertEquals("unknown", result.get(1).targetTypeCode());
        assertEquals("weapon", result.get(2).targetTypeCode());
    }

    @Test void commonDirectTargetsStillWork() {
        var result = PatchChangeExtractor.extract("Increased enemy health by 20%.\nReduced weapon damage by 10%.");
        assertEquals("enemy", result.get(0).targetTypeCode());
        assertEquals("weapon", result.get(1).targetTypeCode());
    }

    @Test void correctedAndNowLoggedAreRecognized() {
        var result = PatchChangeExtractor.extract("Corrected types for some events.\nErrors encountered during initial script run are now logged to the console.");
        assertEquals(2, result.size());
        assertEquals("fix", result.get(0).changeTypeCode());
        assertEquals("modify", result.get(1).changeTypeCode());
    }

    @Test void futureBenefitDoesNotCancelAnAppliedChange() {
        String text = "Disabled NPC collision, which will make it easier to position NPCs.";
        assertEquals(1, PatchChangeExtractor.extract(text).size());
    }

    @Test void namedFeaturesNeedLocalCurrentIntroduction() {
        String feature = "Star Map - Warping to a system now reveals new locations.";
        assertTrue(PatchChangeExtractor.extract(feature).isEmpty());
        var result = PatchChangeExtractor.extract("We've added so much to space in this update, here's a sampler.\n" + feature);
        assertEquals(1, result.size());
        assertEquals("add", result.get(0).changeTypeCode());
        assertEquals(feature, result.get(0).evidenceQuote());
    }

    @Test void historyAndStoreAreNotGameChanges() {
        assertTrue(PatchChangeExtractor.extract("We have added a form on the store where you can sign up.").isEmpty());
        assertTrue(PatchChangeExtractor.extract("It is like a museum of each update, unlocking features as we added them.").isEmpty());
        assertTrue(PatchChangeExtractor.extract("We've added so much in this update.").isEmpty());
    }

    @Test void featureScopeEndsAtUnrelatedParagraph() {
        String text = "We've added many features in this update.\nStar Map - Explore space.\n"
                + "Thank you for playing.\nMerch Store - Find new clothes.";
        assertEquals(1, PatchChangeExtractor.extract(text).size());
    }

    @Test void futureSectionCannotLeakIntoCurrentChanges() {
        String text = "Upcoming Changes\nAdded a weapon.\nCURRENT CHANGES\nFixed a crash.";
        var result = PatchChangeExtractor.extract(text);
        assertEquals(1, result.size());
        assertEquals("fix", result.get(0).changeTypeCode());
        assertTrue(PatchChangeExtractor.extract("The weapon will be added next update.").isEmpty());
    }

    @Test void genericFixesSubheadingDoesNotClearFutureScope() {
        assertTrue(PatchChangeExtractor.extract("Upcoming Changes\nFixes\nFixed a crash.").isEmpty());
    }

    @Test void deploymentSummaryIsNotAnExtraChange() {
        assertTrue(PatchChangeExtractor.extract("A fix is now rolling out for all platforms.").isEmpty());
    }
}
