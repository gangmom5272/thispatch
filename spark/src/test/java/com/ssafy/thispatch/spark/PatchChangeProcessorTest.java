package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.SparkSessions;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatchChangeProcessorTest {
    private static SparkSession spark;
    private static final StructType CHUNKS = new StructType().add("chunk_id", DataTypes.LongType).add("text", DataTypes.StringType);
    @BeforeAll static void start() {
        spark = SparkSessions.builder("patch_change_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }
    private static Map<String, Short> ids(String... codes) {
        Map<String, Short> result = new HashMap<>();
        for (int index = 0; index < codes.length; index++) result.put(codes[index], (short) (index + 41));
        return result;
    }
    private static Map<String, Short> types() { return ids("add", "remove", "modify", "fix", "deprecate"); }
    private static Map<String, Short> directions() { return ids("increase", "decrease", "none", "not_applicable", "unknown"); }
    private static Map<String, Short> targets() { return ids("player", "enemy", "weapon", "item", "skill", "map", "system", "other", "unknown"); }
    private static Dataset<Row> chunks(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), CHUNKS); }
    private static List<Row> extract(Dataset<Row> input) {
        return PatchChangeProcessor.extract(input, types(), directions(), targets()).orderBy("chunk_id", "evidence_quote").collectAsList();
    }

    @Test void realLookupIdsAndGroundedEvidenceSurviveDistributedExtraction() {
        Dataset<Row> input = chunks(RowFactory.create(901L, "Added a weapon.\nIncreased Axebot health."),
                RowFactory.create(902L, "Unsupported narrative remains in its chunk."));
        List<Row> result = extract(input.repartition(2));
        assertEquals(2, result.size());
        assertEquals((short) 41, (short) result.get(0).getAs("change_type_id"));
        assertEquals((short) 43, (short) result.get(0).getAs("target_type_id"));
        assertEquals("valid", result.get(0).getAs("validation_status"));
        assertEquals("partial", result.get(1).getAs("validation_status"));
        assertEquals(result, extract(input.union(input)));
    }

    @Test void missingLookupCodeFailsInsteadOfMakingUpAForeignKey() {
        assertThrows(IllegalArgumentException.class, () -> PatchChangeProcessor.extract(
                chunks(RowFactory.create(901L, "Added a weapon.")), Map.of(), directions(), targets()));
    }

    @Test void conflictingChunkBodiesAndNullBodiesFail() {
        assertThrows(IllegalArgumentException.class, () -> extract(chunks(
                RowFactory.create(901L, "Added a weapon."), RowFactory.create(901L, "Removed a weapon."))));
        assertThrows(IllegalArgumentException.class, () -> extract(chunks(RowFactory.create(901L, null))));
    }

    @Test void emptyChunkInputProducesEmptyChanges() { assertTrue(extract(chunks()).isEmpty()); }

    @Test void headingScopeSurvivesSparkProcessing() {
        StructType schema = CHUNKS.add("heading_path", DataTypes.StringType);
        Dataset<Row> input = spark.createDataFrame(List.of(
                RowFactory.create(901L, "Added a weapon.", "Upcoming Changes"),
                RowFactory.create(902L, "Fixed a crash.", "Patch Notes > Fixes")), schema);
        var result = extract(input.repartition(2));
        assertEquals(1, result.size());
        assertEquals(902L, (long) result.get(0).getAs("chunk_id"));
    }
}
