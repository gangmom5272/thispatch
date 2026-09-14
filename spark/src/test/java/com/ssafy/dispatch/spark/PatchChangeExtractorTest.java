package com.ssafy.dispatch.spark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PatchChangeExtractorTest {
    @Test void explicitOperationsUseExistingLookupCodes() {
        var changes = PatchChangeExtractor.extract("Added a weapon.\nRemoved an item.\nFixed a crash.\nDeprecated a skill.");
        assertEquals(List.of("add", "remove", "fix", "deprecate"), changes.stream().map(PatchChangeExtractor.Change::changeTypeCode).toList());
        assertEquals(List.of("weapon", "item", "unknown", "skill"), changes.stream().map(PatchChangeExtractor.Change::targetTypeCode).toList());
        assertTrue(changes.stream().allMatch(change -> change.directionCode().equals("not_applicable")));
    }

    @Test void separateExplicitClausesBecomeSeparateChanges() {
        String text = "- Increased enemy health by 20% and decreased weapon damage by 10%.";
        var changes = PatchChangeExtractor.extract(text);
        assertEquals(2, changes.size());
        assertEquals("increase", changes.get(0).directionCode());
        assertEquals("decrease", changes.get(1).directionCode());
        assertEquals("enemy", changes.get(0).targetTypeCode());
        assertEquals("weapon", changes.get(1).targetTypeCode());
        assertTrue(changes.stream().allMatch(change -> text.contains(change.evidenceQuote())));
    }

    @Test void beforeAfterNumbersAndDecimalPointsRemainInEvidence() {
        var changes = PatchChangeExtractor.extract("Weapon damage was increased from 1.2 to 1.5.");
        assertEquals(1, changes.size());
        assertEquals("modify", changes.get(0).changeTypeCode());
        assertEquals("increase", changes.get(0).directionCode());
        assertTrue(changes.get(0).evidenceQuote().contains("1.2 to 1.5"));
    }

    @Test void unknownProperNounIsNotGuessedFromAdjacentHeading() {
        var changes = PatchChangeExtractor.extract("Enemies\nIncreased Axebot health by 20%.");
        assertEquals("unknown", changes.get(0).targetTypeCode());
        assertEquals("partial", changes.get(0).validationStatus());
    }

    @Test void bugConditionsDoNotBecomeTargets() {
        var changes = PatchChangeExtractor.extract("Fixed a crash when an enemy fires a weapon on a map.");
        assertEquals("unknown", changes.get(0).targetTypeCode());
    }

    @Test void mixedTargetsAreNotArbitrarilyAssigned() {
        var changes = PatchChangeExtractor.extract("Increased player and enemy health.");
        assertEquals(1, changes.size());
        assertEquals("unknown", changes.get(0).targetTypeCode());
    }

    @Test void improvementDoesNotImplyNumericalIncrease() {
        var change = PatchChangeExtractor.extract("Improved weapon handling.").get(0);
        assertEquals("modify", change.changeTypeCode());
        assertEquals("unknown", change.directionCode());
    }

    @Test void futureChunkIsNotClaimedAsAppliedChanges() {
        assertTrue(PatchChangeExtractor.extract("Upcoming Changes\nAdded a weapon.\nFixed a crash.").isEmpty());
        assertTrue(PatchChangeExtractor.extract("THIS PATCH IS NOT YET LIVE.\nAdded a skill.").isEmpty());
    }

    @Test void negationDoesNotExtractPositiveOperation() {
        assertTrue(PatchChangeExtractor.extract("Weapon damage was not increased.").isEmpty());
    }

    @Test void ambiguousMultiActionSubjectIsNotCombined() {
        assertTrue(PatchChangeExtractor.extract("Weapon damage increased and reload speed decreased.").isEmpty());
    }

    @Test void unsupportedNarrativeRemainsOnlyInChunk() {
        assertTrue(PatchChangeExtractor.extract("Star Map - Discover a new array of missions and locations.").isEmpty());
    }

    @Test void sentenceAndSemicolonBoundariesPreserveSeparateOperations() {
        assertEquals(3, PatchChangeExtractor.extract("Added a skill. Fixed a crash; Reduced enemy health.").size());
    }

    @Test void blankInputIsNotSuccessfulEmptyExtraction() {
        assertThrows(IllegalArgumentException.class, () -> PatchChangeExtractor.extract("  "));
        assertThrows(IllegalArgumentException.class, () -> PatchChangeExtractor.extract(null));
    }
}
