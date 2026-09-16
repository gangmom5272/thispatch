package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.trim;
import static org.apache.spark.sql.functions.udf;

import com.ssafy.thispatch.common.NewsLake;
import com.ssafy.thispatch.common.NewsSchema;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.api.java.UDF4;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/** Connects news_raw rows to the single classifier. Returns a Dataset without loading a database. */
public final class PatchClassificationProcessor {
    private static final String RESULT_COLUMN = "_patch_classification";
    private static final StructType RESULT_SCHEMA = new StructType()
            .add("is_patch", DataTypes.BooleanType, false)
            .add("decision", DataTypes.StringType, false)
            .add("scope", DataTypes.StringType, false)
            .add("stage", DataTypes.IntegerType, false)
            .add("reason", DataTypes.StringType, false)
            .add("evidence", DataTypes.StringType, false);
    private static final List<String> OUTPUT_COLUMNS = List.of(
            "is_patch", "patch_decision", "patch_scope", "patch_stage", "patch_reason", "patch_evidence", "patch_rule_version");

    private PatchClassificationProcessor() {}

    /** Optional source_game_name is the canonical app name supplied by the caller, never inferred from the notice. */
    public static Dataset<Row> classify(Dataset<Row> news) {
        validateInput(news);
        return applyClassification(NewsLake.latest(news));
    }

    /** Classifies every observation without filtering or choosing a latest row. Replaces previous results. */
    public static Dataset<Row> classifyRows(Dataset<Row> news) {
        validateInput(news);
        return applyClassification(news);
    }

    private static Dataset<Row> applyClassification(Dataset<Row> news) {
        Column gameName = Arrays.asList(news.columns()).contains("source_game_name")
                ? col("source_game_name") : lit(null).cast(DataTypes.StringType);
        Column classification = udf((UDF4<String, String, String, String, Row>)
                PatchClassificationProcessor::classifyNotice, RESULT_SCHEMA)
                .apply(gameName, col("title"), col("contents"), col("feed_tags"));

        return news.withColumn(RESULT_COLUMN, classification)
                .withColumn("is_patch", col(RESULT_COLUMN + ".is_patch"))
                .withColumn("patch_decision", col(RESULT_COLUMN + ".decision"))
                .withColumn("patch_scope", col(RESULT_COLUMN + ".scope"))
                .withColumn("patch_stage", col(RESULT_COLUMN + ".stage"))
                .withColumn("patch_reason", col(RESULT_COLUMN + ".reason"))
                .withColumn("patch_evidence", col(RESULT_COLUMN + ".evidence"))
                .withColumn("patch_rule_version", lit(PatchClassifier.RULE_VERSION))
                .drop(RESULT_COLUMN);
    }

    private static Row classifyNotice(String gameName, String title, String contents, String feedTags) {
        List<String> tags = feedTags == null ? List.of() : Arrays.asList(feedTags.split(","));
        PatchClassifier.Result result = PatchClassifier.classify(gameName, title, contents, tags);
        return RowFactory.create(result.isPatch(), result.decision().name(), result.scope().name(),
                result.stage(), result.reason(), result.evidence());
    }

    private static void validateInput(Dataset<Row> news) {
        List<String> columns = Arrays.asList(news.columns());
        for (StructField field : NewsSchema.NEWS_SOURCE.fields()) {
            if (!columns.contains(field.name()) || !news.schema().apply(field.name()).dataType().equals(field.dataType())) {
                throw new IllegalArgumentException("Expected news_raw field: " + field.name() + " " + field.dataType());
            }
        }
        if (columns.contains("source_game_name")
                && !news.schema().apply("source_game_name").dataType().equals(DataTypes.StringType)) {
            throw new IllegalArgumentException("source_game_name must be STRING");
        }
        if (news.filter(col("gid").isNull().or(trim(col("gid")).equalTo(""))
                .or(col("appid").isNull()).or(col("appid").leq(0))
                .or(col("collected_ts").isNull()).or(col("collected_ts").lt(0))).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid news identity or collection timestamp");
        }
        // Equal collection times with different payloads must not select an arbitrary body for classification.
        Dataset<Row> source = news.drop(OUTPUT_COLUMNS.toArray(String[]::new)).drop(RESULT_COLUMN);
        if (source.dropDuplicates().groupBy("gid", "collected_ts").count()
                .filter(col("count").gt(1)).limit(1).count() != 0) {
            throw new IllegalArgumentException("Conflicting news rows at the same gid and collected_ts");
        }
    }
}
