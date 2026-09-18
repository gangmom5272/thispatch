package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.length;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.size;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.storage.StorageLevel;

/**
 * AI 가 쪼개고 임베딩한 패치노트 청크를 서비스 DB 에 적재한다 — {@code patch_chunk}.
 *
 * <pre>
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.PatchChunkToPostgres ... thispatch-spark.jar \
 *       --dt 2026-09-18 | --all   [--dry-run]
 * </pre>
 *
 * <h2>입력 (ai/CONTRACT.md 3-1)</h2>
 *
 * <p>{@code /embeddings/patch_chunk/dt=D/*.parquet} — {@code gid string · seq int16 · text string ·
 * extraction_status · embedding_status string · embedding list<float32>[512] (succeeded 가 아니면 null) ·
 * embedding_model · model_version string · processed_at long(unix초)}. {@code (gid, seq)} 가 자연키,
 * {@code chunk_id} 는 DB 가 매긴다. 같은 (gid, seq) 가 여러 날짜에 다시 나오면(Qwen 백필이 덮어씀)
 * {@code processed_at} 이 가장 늦은 것을 쓴다.
 *
 * <h2>왜 그날 파티션의 gid 만 갈아엎는가</h2>
 *
 * <p>다른 적재기는 표를 통째로 비우고 다시 넣지만, 청크는 행마다 512개 실수 벡터가 붙어 전체를 매일
 * 다시 보내기엔 너무 크다. 그래서 {@code --dt D} 로 그날 파티션만 읽고, 거기 나온 gid 의 청크를 지운 뒤
 * 다시 넣는다 — 한 트랜잭션. {@code patch_change} 가 {@code chunk_id} 를 참조하므로 그 gid 의 변경점을
 * 먼저 지운다 (CONTRACT: 변경점은 삭제 후 재삽입 · {@code patch_change} 적재기가 뒤에 다시 넣는다).
 * 첫 적재만 {@code --all}.
 *
 * <p>{@code embedding vector(512)} 는 백엔드 유사 사례 검색이 {@code <=>} 로 직접 쓰므로 꼭 넣는다.
 * JDBC 는 {@code '[0.1,0.2,…]'::vector} 문자열로 넣는다. FK: gid 는 {@code news} 에 있는 것만.
 * DB 는 드라이버만 만진다({@link LoaderSupport}).
 */
public final class PatchChunkToPostgres {

    /** 벡터가 붙어 행이 크다 — 작게. */
    private static final int BATCH = 200;
    static final int EMBEDDING_DIM = 512;
    private static final double MAX_INVALID_PCT = 1.0;

    private PatchChunkToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        boolean all = false;
        String dt = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case "--dry-run" -> dryRun = true;
                case "--all" -> all = true;
                case "--dt" -> dt = args[++i].trim();
                default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
            }
        }
        if (all == (dt != null)) {
            throw new IllegalArgumentException("--dt YYYY-MM-DD 또는 --all 중 하나를 준다");
        }
        String url = LoaderSupport.env("DB_URL");
        String user = LoaderSupport.env("DB_USER");
        String password = LoaderSupport.env("DB_PASSWORD");

        String root = HdfsPaths.EMBEDDING + "/patch_chunk";
        String path = all ? root : root + "/dt=" + dt;

        SparkSession spark = SparkSessions.build("patch-chunk-to-postgres");
        try {
            if (LoaderSupport.existing(spark, new String[] {path}).isEmpty()) {
                System.out.println("읽을 것이 없다: " + path + " — AI 배치 산출물이 아직 HDFS 에 없다.");
                return;
            }
            System.out.println("읽는 곳   " + path);
            Dataset<Row> raw = spark.read().parquet(path)
                    .select(col("gid"), col("seq").cast(DataTypes.ShortType), col("text"),
                            col("extraction_status"), col("embedding_status"), col("embedding"),
                            col("embedding_model"), col("model_version"), col("processed_at").cast(DataTypes.LongType))
                    .persist(StorageLevel.DISK_ONLY());
            try {
                long total = raw.count();
                System.out.println("청크 행   " + total + "건 (판본 포함)");
                if (total == 0) {
                    System.out.println("행이 없다. 아무것도 하지 않는다.");
                    return;
                }
                long invalidRows = raw.filter(invalidInput()).count();
                if (invalidRows > 0) {
                    double pct = 100.0 * invalidRows / total;
                    System.out.printf("⚠ 넣을 수 없는 행  %d건 (%.4f%%) — 뺀다. 이유별:%n", invalidRows, pct);
                    for (String[] why : new String[][] {
                            {"gid 비었거나 20자 초과", "gid IS NULL OR length(trim(gid)) = 0 OR length(gid) > 20"},
                            {"seq null·음수", "seq IS NULL OR seq < 0"},
                            {"text null", "text IS NULL"},
                            {"status null·10자 초과", "extraction_status IS NULL OR embedding_status IS NULL OR length(extraction_status) > 10 OR length(embedding_status) > 10"},
                            {"embedding 길이 != 512", "embedding IS NOT NULL AND size(embedding) <> " + EMBEDDING_DIM},
                            {"model 50자 초과", "length(embedding_model) > 50 OR length(model_version) > 50"}}) {
                        long c = raw.filter(invalidInput()).filter(why[1]).count();
                        if (c > 0) {
                            System.out.printf("    %-24s %d건%n", why[0], c);
                        }
                    }
                    if (pct > MAX_INVALID_PCT) {
                        throw new IllegalStateException(String.format("넣을 수 없는 행이 %.2f%% 로 한도(%.1f%%)를 넘는다. 데이터를 먼저 볼 것.", pct, MAX_INVALID_PCT));
                    }
                }
                Dataset<Row> latest = latestPerChunk(raw.filter(invalidInput().equalTo(false)))
                        .persist(StorageLevel.DISK_ONLY());
                try {
                    long latestRows = latest.count();
                    System.out.println("(gid, seq) 최신본  " + latestRows + "건");
                    System.out.println("  embedding_status 별:");
                    latest.groupBy("embedding_status").count().orderBy(col("count").desc()).show(10, false);

                    List<String> gids = LoaderSupport.readStrings(url, user, password, "SELECT gid FROM news");
                    System.out.println("news      " + gids.size() + "건 (드라이버가 읽음)");
                    if (gids.isEmpty()) {
                        throw new IllegalStateException("news 표가 비어 있다. FK 때문에 patch_chunk 를 넣을 수 없다 — NewsToPostgres 를 먼저 돌릴 것.");
                    }
                    Dataset<Row> newsGids = spark.createDataset(gids, Encoders.STRING()).toDF("news_gid");
                    Dataset<Row> kept = latest.join(newsGids, latest.col("gid").equalTo(newsGids.col("news_gid")), "left_semi")
                            .persist(StorageLevel.DISK_ONLY());
                    try {
                        long keptRows = kept.count();
                        if (keptRows != latestRows) {
                            System.out.println("⚠ news 에 없는 gid 라 빼는 것  " + (latestRows - keptRows)
                                    + "건 — 외부 기사(RSS)거나 news 적재가 오래됐다");
                        }
                        List<String> loadGids = kept.select("gid").distinct().as(Encoders.STRING()).collectAsList();
                        System.out.println("넣을 것   " + keptRows + "건 · 공지 " + loadGids.size() + "개 (이 gid 들의 기존 청크·변경점은 지우고 다시 넣는다)");
                        if (dryRun) {
                            System.out.println("--dry-run 이라 쓰지 않는다.");
                            kept.select("gid", "seq", "embedding_status", "model_version").show(5, false);
                            return;
                        }
                        long written = replaceChunks(kept, loadGids, url, user, password);
                        long inDb = LoaderSupport.count(url, user, password, "patch_chunk");
                        System.out.println("넣었다    " + written + "건 · DB patch_chunk " + inDb + "행");
                    } finally {
                        kept.unpersist();
                    }
                } finally {
                    latest.unpersist();
                }
            } finally {
                raw.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    // ── 순수 Spark 부분 (테스트가 여기를 본다) ──────────────────────

    /** INSERT 가 거부할 행 — NOT NULL · 길이 · 벡터 차원. */
    static Column invalidInput() {
        return col("gid").isNull().or(length(org.apache.spark.sql.functions.trim(col("gid"))).equalTo(0)).or(length(col("gid")).gt(20))
                .or(col("seq").isNull()).or(col("seq").lt(0))
                .or(col("text").isNull())
                .or(col("extraction_status").isNull()).or(col("embedding_status").isNull())
                .or(length(col("extraction_status")).gt(10)).or(length(col("embedding_status")).gt(10))
                .or(col("embedding").isNotNull().and(size(col("embedding")).notEqual(EMBEDDING_DIM)))
                .or(length(col("embedding_model")).gt(50)).or(length(col("model_version")).gt(50));
    }

    /** (gid, seq) 마다 processed_at 이 가장 늦은 판본 하나. 같으면 임의 — 같은 시각에 다른 내용은 AI 쪽 문제다. */
    static Dataset<Row> latestPerChunk(Dataset<Row> rows) {
        Column rank = row_number().over(Window.partitionBy("gid", "seq")
                .orderBy(col("processed_at").desc_nulls_last()));
        return rows.withColumn("rn", rank).filter(col("rn").equalTo(1)).drop("rn");
    }

    /** pgvector 리터럴 — {@code [0.1,0.2,…]}. */
    static String vectorLiteral(List<Float> values) {
        StringBuilder sb = new StringBuilder(values.size() * 10 + 2).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    // ── DB ─────────────────────────────────────────────────────────

    /**
     * {@code gids} 의 기존 변경점·청크를 지우고 {@code rows} 를 넣는다 — 연결 하나, 트랜잭션 하나.
     * 실패하면 롤백되어 시작 전 그대로다.
     */
    private static long replaceChunks(Dataset<Row> rows, List<String> gids, String url, String user, String password) {
        String insert = """
                INSERT INTO patch_chunk (gid, seq, text, extraction_status, embedding_status, embedding,
                                         embedding_model, model_version, processed_at)
                VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?, ?)
                """;
        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                java.sql.Array gidArray = conn.createArrayOf("varchar", gids.toArray());
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM patch_change WHERE chunk_id IN (SELECT chunk_id FROM patch_chunk WHERE gid = ANY(?))")) {
                    del.setArray(1, gidArray);
                    System.out.println("지웠다    patch_change " + del.executeUpdate() + "행 (이 gid 들의 변경점 — 뒤에서 다시 넣는다)");
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM patch_chunk WHERE gid = ANY(?)")) {
                    del.setArray(1, gidArray);
                    System.out.println("지웠다    patch_chunk " + del.executeUpdate() + "행");
                }
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    int inBatch = 0;
                    Iterator<Row> it = rows.toLocalIterator();
                    while (it.hasNext()) {
                        Row r = it.next();
                        ps.setString(1, r.getAs("gid"));
                        ps.setShort(2, r.<Short>getAs("seq"));
                        ps.setString(3, r.getAs("text"));
                        ps.setString(4, r.getAs("extraction_status"));
                        ps.setString(5, r.getAs("embedding_status"));
                        int embIdx = r.fieldIndex("embedding");
                        if (r.isNullAt(embIdx)) {
                            ps.setNull(6, Types.OTHER);
                        } else {
                            ps.setString(6, vectorLiteral(r.getList(embIdx)));
                        }
                        ps.setString(7, r.getAs("embedding_model"));
                        ps.setString(8, r.getAs("model_version"));
                        int tsIdx = r.fieldIndex("processed_at");
                        if (r.isNullAt(tsIdx)) {
                            ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(9, Timestamp.from(Instant.ofEpochSecond(r.getLong(tsIdx))));
                        }
                        ps.addBatch();
                        if (++inBatch >= BATCH) {
                            n += ps.executeBatch().length;
                            inBatch = 0;
                            if (n % 20_000 == 0) {
                                System.out.println("  … " + n + "건");
                            }
                        }
                    }
                    if (inBatch > 0) {
                        n += ps.executeBatch().length;
                    }
                }
                conn.commit();
                System.out.println("지우고 넣었다  patch_chunk (한 트랜잭션)");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("patch_chunk 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    /** 테스트용 — 리스트를 만들 때 쓰는 헬퍼. */
    static List<Float> floats(int n, float v) {
        List<Float> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(v);
        }
        return out;
    }
}
