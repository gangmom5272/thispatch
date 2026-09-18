package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ssafy.thispatch.common.SparkSessions;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DB 없이 검사할 수 있는 부분 — 최신 판본 선택, 검증 조건, 벡터 리터럴. */
class PatchChunkToPostgresTest {
    private static SparkSession spark;
    /** AI 파케이 스키마 (ai/CONTRACT.md 3-1). */
    private static final StructType CHUNK = new StructType()
            .add("gid", DataTypes.StringType).add("seq", DataTypes.ShortType).add("text", DataTypes.StringType)
            .add("extraction_status", DataTypes.StringType).add("embedding_status", DataTypes.StringType)
            .add("embedding", DataTypes.createArrayType(DataTypes.FloatType))
            .add("embedding_model", DataTypes.StringType).add("model_version", DataTypes.StringType)
            .add("processed_at", DataTypes.LongType);

    @BeforeAll static void start() {
        spark = SparkSessions.builder("patch_chunk_test").master("local[2]")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.driver.host", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
    }
    @AfterAll static void stop() { if (spark != null) spark.stop(); }

    private static Row chunk(String gid, int seq, String text, String embStatus, List<Float> emb, String model, long at) {
        return RowFactory.create(gid, (short) seq, text, "succeeded", embStatus, emb, "embeddinggemma-300m-bf16-512", model, at);
    }
    private static Dataset<Row> input(Row... rows) { return spark.createDataFrame(Arrays.asList(rows), CHUNK); }
    private static final List<Float> V = PatchChunkToPostgres.floats(512, 0.5f);

    @Test void qwenBackfillOverridesRuleVersionByProcessedAt() {
        List<Row> out = PatchChunkToPostgres.latestPerChunk(input(
                        chunk("100", 0, "규칙이 뽑은 문장", "skipped", null, "rule-v1", 1_000L),
                        chunk("100", 0, "Qwen 이 다시 뽑은 문장", "succeeded", V, "qwen3.5-9b-q4km", 2_000L),
                        chunk("100", 1, "두 번째 청크", "succeeded", V, "rule-v1", 1_000L)))
                .orderBy("gid", "seq").collectAsList();
        assertEquals(2, out.size());
        assertEquals("qwen3.5-9b-q4km", out.get(0).getAs("model_version"));
        assertEquals("succeeded", out.get(0).getAs("embedding_status"));
        assertEquals((short) 1, (short) out.get(1).getAs("seq"));
    }

    @Test void invalidInputCatchesWhatInsertWouldReject() {
        Dataset<Row> rows = input(
                chunk("100", 0, "ok", "succeeded", V, "rule-v1", 1L),                           // 정상
                chunk("101", 0, "ok · 임베딩 없음", "skipped", null, "rule-v1", 1L),             // 정상 (skipped 는 null 허용)
                chunk(null, 0, "x", "succeeded", V, "rule-v1", 1L),                            // gid null
                chunk("102", 0, null, "succeeded", V, "rule-v1", 1L),                          // text null
                chunk("103", 0, "x", "succeeded", PatchChunkToPostgres.floats(768, 0.1f), "rule-v1", 1L), // 차원 틀림
                chunk("104", 0, "x", "very-long-status", V, "rule-v1", 1L),                    // status 10자 초과
                chunk("123456789012345678901", 0, "x", "succeeded", V, "rule-v1", 1L));        // gid 21자
        assertEquals(5, rows.filter(PatchChunkToPostgres.invalidInput()).count());
        assertEquals(2, rows.filter(PatchChunkToPostgres.invalidInput().equalTo(false)).count());
    }

    @Test void vectorLiteralIsPgvectorSyntax() {
        String lit = PatchChunkToPostgres.vectorLiteral(Arrays.asList(0.5f, -1.25f, 0f));
        assertEquals("[0.5,-1.25,0.0]", lit);
        String full = PatchChunkToPostgres.vectorLiteral(V);
        assertTrue(full.startsWith("[0.5,0.5,") && full.endsWith(",0.5]"));
        assertEquals(512, full.split(",").length);
    }
}
