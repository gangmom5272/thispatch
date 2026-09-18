package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ssafy.thispatch.common.SparkSessions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DB 없이 검사할 수 있는 부분 — Qwen valid 골라내기, 코드→id, 기본 행 덮어쓰기. */
class PatchChangeToPostgresTest {
    private static SparkSession spark;
    /** AI 파케이 스키마 (ai/CONTRACT.md 3-2) — target · attribute 는 버려지므로 넣지 않아도 select 가 안 본다… 는 아니고, 있어도 없어도 된다. */
    private static final StructType AI = new StructType()
            .add("gid", DataTypes.StringType).add("seq", DataTypes.ShortType).add("change_seq", DataTypes.ShortType)
            .add("change_type", DataTypes.StringType).add("direction", DataTypes.StringType)
            .add("target_type", DataTypes.StringType).add("target", DataTypes.StringType).add("attribute", DataTypes.StringType)
            .add("evidence_quote", DataTypes.StringType).add("validation_status", DataTypes.StringType);
    private static final StructType CHUNK_INFO = new StructType()
            .add("gid", DataTypes.StringType).add("seq", DataTypes.ShortType)
            .add("chunk_id", DataTypes.LongType).add("model_version", DataTypes.StringType);
    private static final Map<String, Short> TYPES = Map.of("add", (short) 1, "remove", (short) 2, "modify", (short) 3, "fix", (short) 4, "deprecate", (short) 5);
    private static final Map<String, Short> DIRS = Map.of("increase", (short) 1, "decrease", (short) 2, "none", (short) 3, "not_applicable", (short) 4, "unknown", (short) 5);
    private static final Map<String, Short> TARGETS = Map.of("player", (short) 1, "enemy", (short) 2, "weapon", (short) 3, "unknown", (short) 9);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("patch_change_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row ai(String gid, int seq, String type, String dir, String target, String status) {
        return RowFactory.create(gid, (short) seq, (short) 0, type, dir, target, "Axebot", "health", "Axebot health 100 -> 80", status);
    }
    private static Row info(String gid, int seq, long chunkId, String model) { return RowFactory.create(gid, (short) seq, chunkId, model); }
    private static Row base(long chunkId, int type) {
        return RowFactory.create(chunkId, (short) type, (short) 5, (short) 9, "규칙이 뽑은 문장", "needs_review");
    }

    @Test void onlyQwenChunksWithValidStatusOverlay() {
        Dataset<Row> aiRows = spark.createDataFrame(Arrays.asList(
                ai("100", 0, "modify", "decrease", "enemy", "valid"),        // qwen 청크 · valid → 들어감
                ai("100", 1, "fix", "not_applicable", "unknown", "valid"),  // rule-v1 청크 → 규칙 판본, 빠짐
                ai("100", 0, "add", "increase", "player", "needs_review"),  // qwen 이지만 valid 아님 → 빠짐
                ai("200", 0, "remove", "none", "weapon", "valid")), AI);    // DB 에 청크 없음 → 빠짐
        Dataset<Row> chunks = spark.createDataFrame(Arrays.asList(
                info("100", 0, 5001L, "qwen3.5-9b-q4km"),
                info("100", 1, 5002L, "rule-v1")), CHUNK_INFO);
        List<Row> out = PatchChangeToPostgres.qwenValidOverlay(aiRows, chunks, TYPES, DIRS, TARGETS).collectAsList();
        assertEquals(1, out.size());
        Row r = out.get(0);
        assertEquals(5001L, (long) r.getAs("chunk_id"));
        assertEquals((short) 3, (short) r.getAs("change_type_id"));   // modify
        assertEquals((short) 2, (short) r.getAs("direction_id"));     // decrease
        assertEquals((short) 2, (short) r.getAs("target_type_id"));   // enemy
        assertEquals("valid", r.getAs("validation_status"));
    }

    @Test void unknownCodeBecomesNullNotAnotherCode() {
        Dataset<Row> aiRows = spark.createDataFrame(List.of(ai("100", 0, "rebalance", "sideways", "npc", "valid")), AI);
        Dataset<Row> chunks = spark.createDataFrame(List.of(info("100", 0, 5001L, "qwen3.5-9b-q4km")), CHUNK_INFO);
        Row r = PatchChangeToPostgres.qwenValidOverlay(aiRows, chunks, TYPES, DIRS, TARGETS).first();
        assertNull(r.getAs("change_type_id"));
        assertNull(r.getAs("direction_id"));
        assertNull(r.getAs("target_type_id"));
    }

    @Test void overlayReplacesAllBaseRowsOfThatChunkOnly() {
        Dataset<Row> baseRows = spark.createDataFrame(Arrays.asList(
                base(5001L, 1), base(5001L, 4),   // 덮어쓰기 대상 청크의 기본 행 둘 → 전부 버림
                base(5002L, 3)), PatchChangeProcessor.OUTPUT_SCHEMA);
        Dataset<Row> overlay = spark.createDataFrame(List.of(
                RowFactory.create(5001L, (short) 3, (short) 2, (short) 2, "Axebot health 100 -> 80", "valid")),
                PatchChangeProcessor.OUTPUT_SCHEMA);
        List<Row> out = PatchChangeToPostgres.merge(baseRows, overlay).orderBy("chunk_id").collectAsList();
        assertEquals(2, out.size());
        assertEquals("valid", out.get(0).getAs("validation_status"));       // 5001 은 Qwen 행 하나
        assertEquals(5002L, (long) out.get(1).getAs("chunk_id"));           // 5002 는 기본 행 그대로
        assertEquals("needs_review", out.get(1).getAs("validation_status"));
    }
}
