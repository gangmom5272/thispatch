package com.ssafy.thispatch.spark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatchChangeSectionerTest {
    @Test void headingsKeepNamesAndTheirDescriptionsInTheSameSection() {
        var sections = PatchChangeSectioner.split("[h1]Changelog[/h1][h2]Weapons[/h2]Slime Spear\n"
                + "- Damage increased from 9 to 12.\nShadowbeam Staff\n- Knockback increased from 3.25 to 6."
                + "[h2]Fixes[/h2]Fixed a crash.");
        assertEquals(2, sections.size());
        assertEquals("Changelog > Weapons", sections.get(0).headingPath());
        assertTrue(sections.get(0).text().contains("Slime Spear\n- Damage"));
        assertTrue(sections.get(0).text().contains("Shadowbeam Staff\n- Knockback"));
        assertFalse(sections.get(1).text().contains("Slime Spear"));
    }

    @Test void nestedListsKeepEntityDescription() {
        var section = PatchChangeSectioner.split("[h2]API[/h2][list][*]Added custom_hud_layout entity:"
                + "[list][*]Custom UI is supported.[*]Events are not supported.[/list][/list]").get(0);
        assertTrue(section.text().contains("custom_hud_layout"));
        assertTrue(section.text().contains("Events are not supported."));
    }

    @Test void missingHeadingDoesNotDropNarrativeIntroduction() {
        var sections = PatchChangeSectioner.split("[p]We've added many features in this update.[/p]"
                + "[p][b]Star Map[/b] - Explore new locations.[/p]");
        assertEquals(1, sections.size());
        var changes = PatchChangeExtractor.extract(sections.get(0).headingPath(), sections.get(0).text());
        assertEquals(1, changes.size());
        assertTrue(sections.get(0).text().contains(changes.get(0).evidenceQuote()));
    }

    @Test void futureAndMerchHeadingsAreStillAppliedAfterSplitting() {
        var sections = PatchChangeSectioner.split("<h2>Upcoming Changes</h2>Added a weapon."
                + "<h2>Merchandise</h2>Added a shirt.<h2>Fixes</h2>Fixed a crash.");
        assertEquals(3, sections.size());
        assertTrue(PatchChangeExtractor.extract(sections.get(0).headingPath(), sections.get(0).text()).isEmpty());
        assertTrue(PatchChangeExtractor.extract(sections.get(1).headingPath(), sections.get(1).text()).isEmpty());
        assertEquals(1, PatchChangeExtractor.extract(sections.get(2).headingPath(), sections.get(2).text()).size());
    }
}
