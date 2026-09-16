package com.ssafy.thispatch.spark;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the decisions that differed from the removed PatchJudge implementation. */
class PatchClassifierContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("classificationCases")
    void preservesUnifiedDecisions(String name, String title, String body, String tags,
            PatchClassifier.Decision expectedDecision, PatchClassifier.Scope expectedScope) {
        var result = PatchClassifier.classify(title, body, Arrays.asList(tags.split(",")));
        assertEquals(expectedDecision, result.decision());
        assertEquals(expectedScope, result.scope());
    }

    static Stream<Arguments> classificationCases() {
        return Stream.of(
                Arguments.of("short-hotfix", "Hotfix", "Fixed a crash.", "", PatchClassifier.Decision.PATCH, PatchClassifier.Scope.DEFAULT),
                Arguments.of("tagged-preview", "Patch Preview", "Fixed a crash in the upcoming build.", "patchnotes", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("substring-tag", "Information", "More news soon.", "not_patchnotes", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("empty-body", "Patch Notes", "", "patchnotes", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("version-only-update", "Update 3.4.0", "More details soon.", "", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("verbs-without-context", "Hello", "Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. Fixed a crash. ", "", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("launch-with-version", "Official Launch 1.0", "Buy the game today.", "", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("standard-tag", "Patch Notes 1.2.3", "[list][*]Fixed a crash[/list]", "patchnotes", PatchClassifier.Decision.PATCH, PatchClassifier.Scope.DEFAULT),
                Arguments.of("update-with-changes", "Update 3.4.0", "Fixed one issue.\nAdded two maps.\nReduced damage.\nChanged UI.\nRemoved bug.", "", PatchClassifier.Decision.PATCH, PatchClassifier.Scope.DEFAULT),
                Arguments.of("test-branch", "Public Test Patch 1.2", "Fixed a crash.", "patchnotes", PatchClassifier.Decision.PATCH, PatchClassifier.Scope.TEST),
                Arguments.of("sale", "Summer Sale is live!", "50% off this week", "", PatchClassifier.Decision.REVIEW_REQUIRED, PatchClassifier.Scope.DEFAULT),
                Arguments.of("structured-changes", "Maintenance", "Patch Notes\nAdded a new map.\nFixed a crash.", "", PatchClassifier.Decision.PATCH, PatchClassifier.Scope.DEFAULT));
    }
}
