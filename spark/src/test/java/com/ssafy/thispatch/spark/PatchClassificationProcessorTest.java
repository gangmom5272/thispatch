package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.lit;
import static org.junit.jupiter.api.Assertions.*;

import com.ssafy.thispatch.common.NewsSchema;
import com.ssafy.thispatch.common.SparkSessions;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchClassificationProcessorTest {
    private static SparkSession spark;

    @BeforeAll static void startSpark() {
        spark = SparkSessions.builder("patch_classification_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }

    @AfterAll static void stopSpark() {
        if (spark != null) spark.stop();
    }

    private static Row notice(String gid, long collected, String title, String body, String tags) {
        return RowFactory.create(gid, 10L, title, body, "https://example.test/news/" + gid,
                100L, collected, "Developer", "steam_community_announcements", "Community Announcements",
                1, tags, false);
    }

    private static Dataset<Row> input(Row... rows) {
        return spark.createDataFrame(Arrays.asList(rows), NewsSchema.NEWS_SOURCE);
    }

    @Test void readsParquetAndPreservesThreeDecisionsAndOriginalFields(@TempDir Path temporary) {
        Dataset<Row> raw = input(
                notice("1", 200, "Hotfix", "[list][*]Fixed a crash.[/list]", null),
                notice("2", 200, "Patch Preview", "Next week.", null),
                notice("3", 200, "Patch Preview", "Next week.", "news, patchnotes"));
        String path = temporary.resolve("news_raw").toString();
        raw.write().parquet(path);
        List<Row> result = PatchClassificationProcessor.classify(spark.read().schema(NewsSchema.NEWS_RAW).parquet(path))
                .orderBy("gid").collectAsList();
        assertEquals(List.of("PATCH", "NOT_PATCH", "REVIEW_REQUIRED"),
                result.stream().map(row -> row.<String>getAs("patch_decision")).toList());
        assertEquals("[list][*]Fixed a crash.[/list]", result.get(0).getAs("contents"));
        assertEquals(100L, (long) result.get(0).getAs("published_ts"));
        assertEquals(200L, (long) result.get(0).getAs("collected_ts"));
        assertEquals(PatchClassifier.RULE_VERSION, result.get(0).getAs("patch_rule_version"));
        assertEquals(List.of(true, false, false), result.stream().map(row -> row.<Boolean>getAs("is_patch")).toList());
        for (Row row : result) assertNotEquals(PatchClassifier.UNJUDGED_REASON, row.getAs("patch_reason"));
    }

    @Test void selectsLatestBodyBeforeClassifyingAndRemovesExactDuplicates() {
        Row latest = notice("1", 300, "Patch Preview", "Next week.", null);
        List<Row> result = PatchClassificationProcessor.classify(input(
                notice("1", 200, "Hotfix", "Fixed a crash.", null), latest, latest)).collectAsList();
        assertEquals(1, result.size());
        assertEquals("NOT_PATCH", result.get(0).getAs("patch_decision"));
        assertEquals(300L, (long) result.get(0).getAs("collected_ts"));
    }

    @Test void retainsMissingBodyForReviewInsteadOfDroppingIt() {
        Row result = PatchClassificationProcessor.classify(input(
                notice("1", 200, "Patch Notes", null, "patchnotes"))).first();
        assertEquals("REVIEW_REQUIRED", result.getAs("patch_decision"));
        assertEquals("MISSING_TITLE_OR_BODY", result.getAs("patch_reason"));
    }

    @Test void splitsFeedTagsAndKeepsTestScope() {
        Row result = PatchClassificationProcessor.classify(input(
                notice("1", 200, "Public Test Maintenance", "Fixed a crash.", "news, patchnotes, event"))).first();
        assertEquals("PATCH", result.getAs("patch_decision"));
        assertEquals("TEST", result.getAs("patch_scope"));
        assertEquals("PATCHNOTES_TAG", result.getAs("patch_reason"));
    }

    @Test void usesOptionalCanonicalGameNameWithoutInferringIt() {
        Dataset<Row> raw = input(notice("1", 200, "Collaboration Update",
                "The Dead Cells update for Astral Ascent is now live!", null));
        assertEquals("REVIEW_REQUIRED", PatchClassificationProcessor.classify(raw).first().getAs("patch_decision"));
        assertEquals("PATCH", PatchClassificationProcessor.classify(
                raw.withColumn("source_game_name", lit("Astral Ascent"))).first().getAs("patch_decision"));
    }

    @Test void rejectsConflictingBodiesAtTheSameCollectionTime() {
        assertThrows(IllegalArgumentException.class, () -> PatchClassificationProcessor.classify(input(
                notice("1", 200, "Hotfix", "Fixed a crash.", null),
                notice("1", 200, "Patch Preview", "Next week.", null))));
    }

    @Test void rejectsMissingOrWrongSourceSchema() {
        Dataset<Row> raw = input(notice("1", 200, "Hotfix", "Fixed a crash.", null));
        assertThrows(IllegalArgumentException.class, () -> PatchClassificationProcessor.classify(raw.drop("feed_tags")));
        assertThrows(IllegalArgumentException.class, () -> PatchClassificationProcessor.classify(raw.withColumn("appid", lit("10"))));
        assertThrows(IllegalArgumentException.class, () -> PatchClassificationProcessor.classify(raw.withColumn("collected_ts", lit(-1L))));
    }

    @Test void reclassificationReplacesUnjudgedAndStaleResultsFromTheSource() {
        Dataset<Row> raw = input(notice("1", 200, "Hotfix", "Fixed a crash.", null));
        Dataset<Row> unjudged = raw.withColumn("is_patch", lit(false))
                .withColumn("patch_reason", lit(PatchClassifier.UNJUDGED_REASON));
        Row patch = PatchClassificationProcessor.classifyRows(unjudged).first();
        assertTrue((boolean) patch.getAs("is_patch"));
        assertNotEquals(PatchClassifier.UNJUDGED_REASON, patch.getAs("patch_reason"));

        Dataset<Row> changedBody = PatchClassificationProcessor.classifyRows(raw)
                .withColumn("title", lit("Patch Preview")).withColumn("contents", lit("Next week."));
        Row preview = PatchClassificationProcessor.classifyRows(changedBody).first();
        assertFalse((boolean) preview.getAs("is_patch"));
        assertEquals("NOT_PATCH", preview.getAs("patch_decision"));
    }

    @Test void emptyInputKeepsTypedOutput() {
        Dataset<Row> result = PatchClassificationProcessor.classify(input());
        assertEquals(0, result.count());
        assertTrue(Arrays.asList(result.columns()).contains("patch_decision"));
        assertEquals(org.apache.spark.sql.types.DataTypes.BooleanType, result.schema().apply("is_patch").dataType());
    }
}
