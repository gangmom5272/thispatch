package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.length;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.lower;
import static org.apache.spark.sql.functions.trim;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;

/**
 * 패치 변경점을 서비스 DB 에 적재한다 — {@code patch_change}.
 *
 * <pre>
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.PatchChangeToPostgres ... thispatch-spark.jar \
 *       --chunk-dt 2026-09-17 --change-dt 2026-09-18 | --all   [--dry-run]
 * </pre>
 *
 * <h2>두 입력을 겹친다 (진우님 확정 · 2026-09-18 · CONTRACT 3-2 · 4-2)</h2>
 *
 * <ol>
 *   <li><b>기본</b> — Spark {@link PatchChangeExtractor}(change-rules-2)를 모든 청크 텍스트에 돌린 결과.
 *       청크는 {@code /embeddings/patch_chunk/dt=D} 파케이에서 읽고, {@code chunk_id} 는 DB 의
 *       {@code patch_chunk} 에서 (gid, seq)→chunk_id 로 바꾼다 — {@link PatchChunkToPostgres} 가 먼저 돌아야 한다.</li>
 *   <li><b>덮어쓰기</b> — AI {@code /embeddings/patch_change/dt=D} 중 {@code model_version} 이
 *       {@code qwen} 으로 시작하고 {@code validation_status = 'valid'} 인 행. 같은 (gid, seq) 의 기본 행을
 *       전부 버리고 이것으로 바꾼다. AI 의 규칙 판본(rule-v2) 행은 넣지 않는다.
 *       {@code model_version} 은 변경점 파일의 컬럼(2026-09-18 추가, 행 단위)을 먼저 보고, 없는 옛 파일이면
 *       청크의 {@code model_version} 으로 판단한다 — 한 파일에 rule-v2 와 Qwen 행이 섞여 오므로 행 단위가 맞다.</li>
 * </ol>
 *
 * <p>Qwen 행은 인기 게임·최근 공지부터 며칠에 걸쳐 늘어나므로 매일 다시 돈다 — 그날 파티션의 gid 에 대해
 * 변경점을 지우고 다시 넣는다(한 트랜잭션). 코드표(change_type · direction · target_type)는 DB 가
 * 정본이라 드라이버가 읽어 code→id 로 바꾼다 — 시드 순서를 믿지 않는다 ({@link PatchChangeProcessor}).
 * {@code target} · {@code attribute} 는 ERD 미채택이라 버린다 (CONTRACT 3-2).
 *
 * <p>DB 는 드라이버만 만진다({@link LoaderSupport}).
 */
public final class PatchChangeToPostgres {

    private static final int BATCH = 1_000;

    /** DB 에서 읽은 (gid, seq, chunk_id) 의 스키마. */
    static final StructType CHUNK_KEY = new StructType()
            .add("gid", DataTypes.StringType, false)
            .add("seq", DataTypes.ShortType, false)
            .add("chunk_id", DataTypes.LongType, false);

    private PatchChangeToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        boolean all = false;
        String chunkDt = null;
        String changeDt = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case "--dry-run" -> dryRun = true;
                case "--all" -> all = true;
                case "--chunk-dt" -> chunkDt = args[++i].trim();
                case "--change-dt" -> changeDt = args[++i].trim();
                default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
            }
        }
        if (all == (chunkDt != null)) {
            throw new IllegalArgumentException("--chunk-dt YYYY-MM-DD (와 선택으로 --change-dt) 또는 --all 중 하나");
        }
        String url = LoaderSupport.env("DB_URL");
        String user = LoaderSupport.env("DB_USER");
        String password = LoaderSupport.env("DB_PASSWORD");

        String chunkPath = HdfsPaths.EMBEDDING + "/patch_chunk" + (all ? "" : "/dt=" + chunkDt);
        String changePath = HdfsPaths.EMBEDDING + "/patch_change"
                + (all ? "" : "/dt=" + (changeDt != null ? changeDt : chunkDt));

        SparkSession spark = SparkSessions.build("patch-change-to-postgres");
        try {
            if (LoaderSupport.existing(spark, new String[] {chunkPath}).isEmpty()) {
                System.out.println("읽을 것이 없다: " + chunkPath);
                return;
            }
            boolean hasAiChanges = !LoaderSupport.existing(spark, new String[] {changePath}).isEmpty();
            System.out.println("읽는 곳   청크 " + chunkPath + " · AI 변경점 " + changePath + (hasAiChanges ? "" : " (없음 — 기본만)"));

            // 청크: (gid, seq) 최신 판본의 text · model_version. heading_path 는 CONTRACT 에 없지만 있으면 쓴다.
            Dataset<Row> chunkRaw = spark.read().parquet(chunkPath);
            boolean hasHeading = java.util.Arrays.asList(chunkRaw.columns()).contains("heading_path");
            Dataset<Row> chunks = PatchChunkToPostgres.latestPerChunk(chunkRaw
                            .select(col("gid"), col("seq").cast(DataTypes.ShortType), col("text"), col("model_version"),
                                    col("processed_at").cast(DataTypes.LongType),
                                    (hasHeading ? col("heading_path") : lit(null).cast(DataTypes.StringType)).alias("heading_path"))
                            .filter(col("gid").isNotNull().and(col("seq").isNotNull())))
                    .filter(col("text").isNotNull().and(length(trim(col("text"))).gt(0)))
                    .persist(StorageLevel.DISK_ONLY());
            try {
                long chunkRows = chunks.count();
                System.out.println("청크 최신본  " + chunkRows + "건 (text 있는 것)");
                if (chunkRows == 0) {
                    System.out.println("청크가 없다. 아무것도 하지 않는다.");
                    return;
                }

                // (gid, seq) → chunk_id 는 DB 가 정본이다. 그날 gid 만 읽는다.
                List<String> gids = chunks.select("gid").distinct().as(Encoders.STRING()).collectAsList();
                List<Row> keys = readChunkKeys(url, user, password, gids);
                System.out.println("공지 " + gids.size() + "개 · DB patch_chunk 키 " + keys.size() + "건 (드라이버가 읽음)");
                if (keys.isEmpty()) {
                    throw new IllegalStateException("patch_chunk 에 이 gid 들의 청크가 없다 — PatchChunkToPostgres 를 먼저 돌릴 것.");
                }
                Dataset<Row> chunkKeys = spark.createDataFrame(keys, CHUNK_KEY);
                Map<String, Short> changeTypes = readCodes(url, user, password, "patch_change_type", "change_type_id");
                Map<String, Short> directions = readCodes(url, user, password, "patch_change_direction", "direction_id");
                Map<String, Short> targets = readCodes(url, user, password, "patch_change_target_type", "target_type_id");

                Dataset<Row> withId = chunks.join(chunkKeys, new String[] {"gid", "seq"});
                long unmapped = chunkRows - withId.count();
                if (unmapped > 0) {
                    System.out.println("DB 에 chunk_id 가 없는 파케이 행  " + unmapped + "건 — 빠진다. 대부분 같은 청크의 옛 판본(파케이는 판본을 모두 담고 DB 는 최신본만 든다 · 2026-09-21 실측 109만). 최신본이 빠지는 것이면 patch_chunk 적재가 이 파티션보다 오래된 것이다");
                }

                // 1) 기본: 규칙 추출기. 결과는 chunk_id 기준이다.
                Dataset<Row> base = PatchChangeProcessor.extract(withId.select("chunk_id", "text", "heading_path"), changeTypes, directions, targets)
                        .persist(StorageLevel.DISK_ONLY());
                // 2) 덮어쓰기: AI Qwen valid 행. (gid, seq) 로 오므로 chunk_id 와 model_version 을 붙인다.
                Dataset<Row> overlay = hasAiChanges
                        ? qwenValidOverlay(spark.read().parquet(changePath), withId.select("gid", "seq", "chunk_id", "model_version"),
                                           changeTypes, directions, targets).persist(StorageLevel.MEMORY_AND_DISK())
                        : spark.createDataFrame(new ArrayList<>(), PatchChangeProcessor.OUTPUT_SCHEMA);
                try {
                    long baseRows = base.count();
                    long overlayRows = overlay.count();
                    Dataset<Row> merged = merge(base, overlay).persist(StorageLevel.DISK_ONLY());
                    try {
                        long rows = merged.count();
                        long overlaidChunks = overlay.select("chunk_id").distinct().count();
                        System.out.println("기본(규칙) " + baseRows + "건 · Qwen valid " + overlayRows + "건 (청크 " + overlaidChunks + "개 덮어씀) → 넣을 것 " + rows + "건");
                        long nullType = merged.filter(col("change_type_id").isNull()).count();
                        if (nullType > 0) {
                            System.out.println("⚠ change_type 코드가 코드표에 없어 null 인 행  " + nullType + "건 — 백엔드 검색에 안 잡힌다");
                        }
                        merged.groupBy("validation_status").count().orderBy(col("count").desc()).show(10, false);
                        if (dryRun) {
                            System.out.println("--dry-run 이라 쓰지 않는다.");
                            return;
                        }
                        long written = replaceChanges(merged, gids, url, user, password);
                        long inDb = LoaderSupport.count(url, user, password, "patch_change");
                        System.out.println("넣었다    " + written + "건 · DB patch_change " + inDb + "행");
                    } finally {
                        merged.unpersist();
                    }
                } finally {
                    base.unpersist();
                    overlay.unpersist();
                }
            } finally {
                chunks.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    // ── 순수 Spark 부분 (테스트가 여기를 본다) ──────────────────────

    /**
     * AI 변경점 중 넣을 것만 — model_version 이 qwen 으로 시작하고 validation_status 가 valid.
     * model_version 은 변경점 행의 것을 먼저, 그 컬럼이 없거나 null 이면 청크의 것을 본다.
     * 코드 문자열은 코드표 id 로 바꾼다(없는 코드는 null). 결과는 {@link PatchChangeProcessor#OUTPUT_SCHEMA} 모양.
     */
    static Dataset<Row> qwenValidOverlay(Dataset<Row> aiChanges, Dataset<Row> chunkInfo,
                                         Map<String, Short> changeTypes, Map<String, Short> directions, Map<String, Short> targets) {
        boolean fileHasModel = java.util.Arrays.asList(aiChanges.columns()).contains("model_version");
        Dataset<Row> joined = aiChanges
                .select(col("gid"), col("seq").cast(DataTypes.ShortType), col("change_type"), col("direction"),
                        col("target_type"), col("evidence_quote"), col("validation_status"),
                        (fileHasModel ? col("model_version") : lit(null).cast(DataTypes.StringType)).alias("row_model_version"))
                .join(chunkInfo.withColumnRenamed("model_version", "chunk_model_version"), new String[] {"gid", "seq"})
                .filter(lower(coalesce(col("row_model_version"), col("chunk_model_version"), lit(""))).startsWith("qwen")
                        .and(col("validation_status").equalTo("valid"))
                        .and(col("evidence_quote").isNotNull()));
        return joined.select(
                col("chunk_id"),
                codeToId(col("change_type"), changeTypes).alias("change_type_id"),
                codeToId(col("direction"), directions).alias("direction_id"),
                codeToId(col("target_type"), targets).alias("target_type_id"),
                col("evidence_quote"),
                col("validation_status"));
    }

    /** 덮어쓰기 대상 청크의 기본 행은 버리고, 덮어쓰기 행을 붙인다. */
    static Dataset<Row> merge(Dataset<Row> base, Dataset<Row> overlay) {
        Dataset<Row> overlaid = overlay.select("chunk_id").distinct();
        Dataset<Row> keptBase = base.join(overlaid, base.col("chunk_id").equalTo(overlaid.col("chunk_id")), "left_anti");
        return keptBase.unionByName(overlay);
    }

    /** 코드 문자열 → id. 코드표에 없으면 null — 조용히 다른 코드로 바꾸지 않는다. */
    private static org.apache.spark.sql.Column codeToId(org.apache.spark.sql.Column code, Map<String, Short> ids) {
        org.apache.spark.sql.Column result = lit(null).cast(DataTypes.ShortType);
        for (Map.Entry<String, Short> e : ids.entrySet()) {
            result = org.apache.spark.sql.functions.when(code.equalTo(e.getKey()), lit(e.getValue()).cast(DataTypes.ShortType)).otherwise(result);
        }
        return result;
    }

    // ── DB ─────────────────────────────────────────────────────────

    private static List<Row> readChunkKeys(String url, String user, String password, List<String> gids) {
        List<Row> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement("SELECT gid, seq, chunk_id FROM patch_chunk WHERE gid = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("varchar", gids.toArray()));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(RowFactory.create(rs.getString(1), rs.getShort(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("patch_chunk 키 조회 실패: " + e.getMessage(), e);
        }
        return out;
    }

    static Map<String, Short> readCodes(String url, String user, String password, String table, String idColumn) {
        Map<String, Short> out = new HashMap<>();
        // 표·열 이름은 이 파일 안의 상수다.
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT code, " + idColumn + " FROM " + table)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getShort(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(table + " 조회 실패: " + e.getMessage(), e);
        }
        return out;
    }

    /** gids 의 청크에 붙은 변경점을 지우고 rows 를 넣는다 — 한 트랜잭션. */
    private static long replaceChanges(Dataset<Row> rows, List<String> gids, String url, String user, String password) {
        String insert = """
                INSERT INTO patch_change (chunk_id, change_type_id, direction_id, target_type_id, evidence_quote, validation_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM patch_change WHERE chunk_id IN (SELECT chunk_id FROM patch_chunk WHERE gid = ANY(?))")) {
                    del.setArray(1, conn.createArrayOf("varchar", gids.toArray()));
                    System.out.println("지웠다    patch_change " + del.executeUpdate() + "행 (이 gid 들의 기존 변경점)");
                }
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    int inBatch = 0;
                    Iterator<Row> it = rows.toLocalIterator();
                    while (it.hasNext()) {
                        Row r = it.next();
                        ps.setLong(1, r.<Long>getAs("chunk_id"));
                        setShort(ps, 2, r, "change_type_id");
                        setShort(ps, 3, r, "direction_id");
                        setShort(ps, 4, r, "target_type_id");
                        ps.setString(5, r.getAs("evidence_quote"));
                        ps.setString(6, r.getAs("validation_status"));
                        ps.addBatch();
                        if (++inBatch >= BATCH) {
                            n += ps.executeBatch().length;
                            inBatch = 0;
                            if (n % 200_000 == 0) {
                                System.out.println("  … " + n + "건");
                            }
                        }
                    }
                    if (inBatch > 0) {
                        n += ps.executeBatch().length;
                    }
                }
                conn.commit();
                System.out.println("지우고 넣었다  patch_change (한 트랜잭션)");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("patch_change 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    private static void setShort(PreparedStatement ps, int idx, Row r, String field) throws SQLException {
        int i = r.fieldIndex(field);
        if (r.isNullAt(i)) {
            ps.setNull(idx, Types.SMALLINT);
        } else {
            ps.setShort(idx, r.getShort(i));
        }
    }
}
