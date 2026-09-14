package com.ssafy.dispatch.spark;

import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import static org.apache.spark.sql.functions.*;

/** Distributed extraction; numeric FK values come from the actual lookup tables, never seed order. */
public final class PatchChangeProcessor {
    public static final StructType OUTPUT_SCHEMA = new StructType()
            .add("chunk_id", DataTypes.LongType, false)
            .add("change_type_id", DataTypes.ShortType, true)
            .add("direction_id", DataTypes.ShortType, true)
            .add("target_type_id", DataTypes.ShortType, true)
            .add("evidence_quote", DataTypes.StringType, false)
            .add("validation_status", DataTypes.StringType, false);

    private PatchChangeProcessor() {}

    /** Caller supplies only approved, current chunks and lookup code->ID maps read on the driver. */
    public static Dataset<Row> extract(Dataset<Row> chunks, Map<String, Short> changeTypes,
                                      Map<String, Short> directions, Map<String, Short> targets) {
        if (!chunks.schema().apply("chunk_id").dataType().equals(DataTypes.LongType)
                || !chunks.schema().apply("text").dataType().equals(DataTypes.StringType)) {
            throw new IllegalArgumentException("Expected chunk_id LONG and text STRING");
        }
        boolean hasHeading = java.util.Arrays.asList(chunks.columns()).contains("heading_path");
        if (hasHeading && !chunks.schema().apply("heading_path").dataType().equals(DataTypes.StringType)) {
            throw new IllegalArgumentException("Expected heading_path STRING");
        }
        Dataset<Row> input = chunks.select(col("chunk_id"), col("text"),
                (hasHeading ? col("heading_path") : lit(null).cast(DataTypes.StringType)).alias("heading_path")).dropDuplicates();
        if (input.filter(col("chunk_id").isNull().or(col("chunk_id").leq(0))
                .or(col("text").isNull()).or(length(trim(col("text"))).equalTo(0))).limit(1).count() != 0
                || input.groupBy("chunk_id").count().filter(col("count").gt(1)).limit(1).count() != 0) {
            throw new IllegalArgumentException("Invalid or conflicting patch_chunk input");
        }
        HashMap<String, Short> typeIds = checkedCodes(changeTypes, "add", "remove", "modify", "fix", "deprecate");
        HashMap<String, Short> directionIds = checkedCodes(directions, "increase", "decrease", "not_applicable", "unknown");
        HashMap<String, Short> targetIds = checkedCodes(targets, "player", "enemy", "weapon", "item", "skill", "map", "system", "unknown");
        return input.flatMap((FlatMapFunction<Row, Row>) chunk -> {
            ArrayList<Row> result = new ArrayList<>();
            for (PatchChangeExtractor.Change change : PatchChangeExtractor.extract(chunk.getString(2), chunk.getString(1))) {
                result.add(RowFactory.create(chunk.getLong(0), typeIds.get(change.changeTypeCode()),
                        directionIds.get(change.directionCode()), targetIds.get(change.targetTypeCode()),
                        change.evidenceQuote(), change.validationStatus()));
            }
            return result.iterator();
        }, Encoders.row(OUTPUT_SCHEMA));
    }

    private static HashMap<String, Short> checkedCodes(Map<String, Short> supplied, String... required) {
        HashMap<String, Short> codes = new HashMap<>(supplied);
        for (String code : required) {
            if (codes.get(code) == null || codes.get(code) <= 0) {
                throw new IllegalArgumentException("Missing positive lookup ID for code: " + code);
            }
        }
        if (codes.values().stream().distinct().count() != codes.size()) {
            throw new IllegalArgumentException("Different lookup codes must not share an ID");
        }
        return codes;
    }
}
