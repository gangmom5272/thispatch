package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.ReviewLake;
import com.ssafy.thispatch.common.ReviewSchema;
import com.ssafy.thispatch.common.SparkSessions;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.hash;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.pmod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;

/**
 * 주 1회 {@code delta} 를 {@code base} 에 합쳐 파일 개수를 줄인다.
 *
 * <pre>
 *   /review_raw/base/ + /review_raw/delta/dt=*  ->  /review_raw/base/   (delta 는 비운다)
 * </pre>
 *
 * <p><b>하는 일은 이력 정리가 아니라 파일 통합이다.</b> 같은 리뷰의 옛 판을 지우지
 * 않는다. {@code daily_stat.edited_review_count} 는 「그날 고쳐진 리뷰가 몇 건인가」라서
 * 옛 판이 사라지면 지난 날짜를 다시 만들 수 없다. {@link ReviewSchema#DEDUP_KEY} 로
 * 완전히 같은 레코드만 한 벌로 줄인다.
 *
 * <p>왜 필요한가 — 증분 수집은 매일 {@code delta/dt=날짜} 를 하나씩 만든다. 1년이면
 * 365개고, 안에 든 데이터가 아무리 작아도 Spark 가 파일을 여는 비용만으로 느려진다.
 *
 * <p>실행
 *
 * <pre>
 *   spark-submit --class com.ssafy.thispatch.spark.Compaction thispatch-spark.jar
 *   ... thispatch-spark.jar --dry-run     무엇을 할지만 보고 아무것도 바꾸지 않는다
 *   ... thispatch-spark.jar --buckets 4   조각 수 (기본 4)
 *   ... thispatch-spark.jar --fresh       지난 실행이 남긴 조각을 버리고 처음부터
 * </pre>
 *
 * <p><b>조각으로 나눠 하고, 끊기면 이어한다 (2026-09-22).</b> 전에는 중복 제거 결과를
 * 메모리·셔플에만 들고 있다가 한 잡으로 base 를 썼다. 무선 클러스터에서 그 잡이 죽으면
 * Spark 가 임시 폴더를 통째로 비워 몇 시간 쓴 것이 0 이 됐다 — 09-21 밤 두 번 시도에
 * 14시간 반을 쓰고 아무것도 남지 않았다. 지금은
 * <ol>
 *   <li>{@code recommendationid} 의 해시로 리뷰를 조각 N개로 나눠, 조각마다 <b>따로</b>
 *       중복 제거해 {@code /review_raw/.compaction/bucket-i} 에 확정한다. 같은 리뷰는
 *       늘 같은 조각에 떨어지므로 조각별 중복 제거 = 전체 중복 제거다. 조각 하나는
 *       독립된 잡이라 하나가 죽어도 끝난 조각은 남는다.</li>
 *   <li>다시 실행하면 입력(delta 날짜 · base 파일)이 같은지 {@code _manifest} 로 확인하고,
 *       {@code _SUCCESS} 가 있는 조각은 건너뛴다.</li>
 *   <li>조각이 다 되면 파일을 staging 으로 <b>이름만 바꿔</b> 모은다(메타데이터 연산,
 *       즉시). 세어서 맞으면 base 와 바꿔치기하고 delta 를 비운다.</li>
 * </ol>
 *
 * <p>⚠ 집계 잡과 <b>같이 돌리면 안 된다.</b> 바꿔치기하는 찰나에 집계가 읽으면
 * {@code spark.sql.files.ignoreMissingFiles} 때문에 <b>적게 읽고도 오류가 안 난다.</b>
 * 화면 숫자만 조용히 줄어든다. 오케스트레이션(S15P21A202-26)에서 순서를 잡는다.
 */
public final class Compaction {

    private Compaction() {
    }

    /**
     * 파일 하나에 담을 대략의 행 수.
     *
     * <p>너무 잘게 쪼개면 여는 비용이 들고, 너무 크게 묶으면 읽을 때 병렬도가 떨어진다.
     * 클러스터 vcore 가 30 이라 1.5억 건이면 75개쯤 나온다.
     */
    private static final long ROWS_PER_FILE = 2_000_000L;

    private static final int MAX_FILES = 200;

    /** 조각 수 기본값. 조각마다 입력을 한 번씩 다시 읽으므로 너무 많으면 읽기가 늘어난다. */
    static final int DEFAULT_BUCKETS = 4;

    /** 조각을 두는 작업 폴더. 점으로 시작해 {@code /review_raw/*} 를 읽는 어떤 잡에도 안 잡힌다. */
    static final String WORK_DIR = HdfsPaths.HDFS + "/review_raw/.compaction";

    static final String MANIFEST = "_manifest";

    /**
     * 스냅샷을 몇 개까지 남길 것인가.
     *
     * <p>스냅샷은 복사가 아니라 <b>바뀐 것만</b> 기록한다(copy-on-write). compaction 은
     * 주 1회라 4개면 한 달치 되돌릴 창이 생기는데 용량은 거의 안 든다.
     */
    private static final int KEEP_SNAPSHOTS = 4;

    public static void main(String[] args) throws IOException {
        boolean dryRun = false;
        boolean snapshot = true;
        boolean fresh = false;
        int buckets = DEFAULT_BUCKETS;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i].trim();
            switch (arg) {
                case "--dry-run" -> dryRun = true;
                // ⚠ 스냅샷 없이 돌리는 것은 되돌릴 수단을 버리는 것이다.
                //   복구는 landing 에서 전부 다시 만드는 길밖에 안 남는다.
                case "--no-snapshot" -> snapshot = false;
                case "--fresh" -> fresh = true;
                case "--buckets" -> buckets = Integer.parseInt(args[++i].trim());
                default -> { }
            }
        }
        if (buckets < 1) {
            throw new IllegalArgumentException("--buckets 는 1 이상이어야 한다: " + buckets);
        }

        SparkSession spark = SparkSessions.build("compaction");
        try {
            FileSystem fs = fileSystem(spark);

            Path base = new Path(HdfsPaths.REVIEW_BASE);
            Path delta = new Path(HdfsPaths.REVIEW_DELTA);
            Path work = new Path(WORK_DIR);

            int deltaPartitions = countPartitions(fs, delta);
            if (deltaPartitions == 0) {
                System.out.println("delta 에 합칠 것이 없다. 아무것도 하지 않는다.");
                return;
            }
            System.out.println("delta 날짜 " + deltaPartitions + "개 · base 파일 "
                    + countFiles(fs, base) + "개");

            // 원본 행 수는 parquet 메타데이터로 세서 빠르다. 파일 수는 여기서 정한다
            // (중복이 빠지면 조금 작아질 뿐이다).
            Dataset<Row> lake = ReviewLake.read(spark);
            long rawRows = lake.count();
            int files = plannedFiles(rawRows);
            int perBucket = perBucket(files, buckets);
            System.out.println("원본 " + rawRows + "건(중복 포함) · 조각 " + buckets + "개 × 파일 "
                    + perBucket + "개 = " + (perBucket * buckets) + "개로 쓴다");

            // 이어하기 — 입력이 그대로일 때만. delta 에 날짜가 하나라도 늘었으면 처음부터.
            String manifest = manifestOf(fs, base, delta);
            if (fs.exists(work)) {
                String before = readText(fs, new Path(work, MANIFEST));
                if (!fresh && manifest.equals(before)) {
                    System.out.println("이어함  " + work + " — 지난 실행이 확정한 조각은 다시 만들지 않는다");
                } else {
                    System.out.println((fresh ? "--fresh" : "입력이 달라졌다") + " → 지난 작업 폴더를 지우고 처음부터: " + work);
                    if (!dryRun) {
                        fs.delete(work, true);
                    }
                }
            }

            if (dryRun) {
                System.out.println("--dry-run 이라 여기서 멈춘다.");
                return;
            }
            fs.mkdirs(work);
            writeText(fs, new Path(work, MANIFEST), manifest);

            // ⚠ 조각마다 독립된 잡이다. 하나가 죽어도 _SUCCESS 가 있는 조각은 남는다.
            //   같은 recommendationid 는 늘 같은 조각에 떨어지므로 조각별 중복 제거는 전체와 같다.
            for (int b = 0; b < buckets; b++) {
                Path out = bucketPath(work, b);
                if (bucketDone(fs, out)) {
                    System.out.println("조각 " + b + "/" + buckets + " 이미 있다 — 건너뜀");
                    continue;
                }
                fs.delete(out, true);
                long t0 = System.currentTimeMillis();
                ReviewLake.all(bucketOf(lake, buckets, b))
                        .coalesce(perBucket)
                        .write().mode(SaveMode.Overwrite).parquet(out.toString());
                System.out.println("조각 " + b + "/" + buckets + " 끝  "
                        + ((System.currentTimeMillis() - t0) / 60_000) + "분  " + out);
            }

            long rows = spark.read().schema(ReviewSchema.REVIEW_RAW)
                    .parquet(bucketPaths(work, buckets)).count();
            System.out.println("합치면 " + rows + "건");

            // ⚠ 건드리기 전에 스냅샷부터 찍는다.
            //
            //   복제 3벌은 노드가 죽는 것을 막아 줄 뿐, 잡이 잘못 지우는 것은
            //   못 막는다 — 지우면 3벌이 다 지워진다. 휴지통도 꺼져 있고,
            //   Java 의 fs.delete() 는 켜져 있어도 휴지통을 쓰지 않는다
            //   (셸의 hdfs dfs -rm 만 거친다).
            //
            //   2026-09-15 확인 — 휴지통 꺼짐 · 스냅샷 없음 · /review_raw 백업 없음.
            //   그 상태에서 되돌릴 수단은 landing 에서 전부 다시 만드는 것뿐이었다.
            if (snapshot) {
                takeSnapshot(fs, new Path(HdfsPaths.HDFS + "/review_raw"));
            } else {
                System.out.println("⚠ 스냅샷 없이 돌린다 (--no-snapshot). 되돌릴 수단이 없다.");
            }

            // ⚠ base 를 바로 덮어쓰면 안 된다. 읽고 있는 것을 지우는 셈이라 운이 나쁘면
            //   base 를 통째로 잃는다. 옆에 모아 두고 이름만 바꾼다 — HDFS 의 rename 은
            //   메타데이터만 건드려서 즉시 끝난다. 조각 파일을 staging 으로 옮기는 것도 rename 이다.
            String stamp = String.valueOf(System.currentTimeMillis());
            Path staging = new Path(HdfsPaths.REVIEW_BASE + ".staging-" + stamp);
            Path retired = new Path(HdfsPaths.REVIEW_BASE + ".old-" + stamp);
            int moved = moveParquetFiles(fs, work, buckets, staging);
            System.out.println("쓴다    " + staging + "  (조각 파일 " + moved + "개를 옮겼다)");

            // ⚠ 바꿔치기 전에 센다. 쓰다 만 것을 base 로 올리면 조용히 데이터를 잃는다.
            long written = spark.read().schema(ReviewSchema.REVIEW_RAW)
                    .parquet(staging.toString()).count();
            if (written != rows) {
                throw new IllegalStateException(
                        "쓴 것이 읽은 것과 다르다 (" + rows + " -> " + written + "). "
                                + "base 를 건드리지 않았다. 남은 것: " + staging);
            }
            System.out.println("확인    " + written + "건 (읽은 것과 같다)");

            // 바꿔치기 — base 를 밀어 두고 새 것을 올린다
            if (fs.exists(base) && !fs.rename(base, retired)) {
                throw new IOException("옛 base 를 밀어내지 못했다: " + base);
            }
            if (!fs.rename(staging, base)) {
                // 되돌린다. 여기서 멈추면 base 가 없는 상태가 된다.
                if (fs.exists(retired)) {
                    fs.rename(retired, base);
                }
                throw new IOException("새 base 를 올리지 못했다. 되돌렸다.");
            }
            System.out.println("바꿨다  " + base);

            // 여기까지 왔으면 base 가 delta 를 전부 품고 있다. 이제 지워도 된다.
            deleteChildren(fs, delta);
            System.out.println("비웠다  " + delta);

            fs.delete(work, true);
            System.out.println("치웠다  " + work);

            // ⚠ 방금 밀어낸 base 는 지우지 않는다. 다음 compaction 때까지 남긴다.
            //
            //   행 수는 맞는데 내용이 잘못된 경우(스키마 버그 같은 것)에는 세는
            //   것으로 못 잡는다. 그때 되돌릴 한 벌이 필요하다. 지난 세대만
            //   지운다 — 한 벌 값이면 일주일짜리 되돌림 창을 산다.
            int swept = sweepOldBases(fs, retired);
            System.out.println("남겼다  " + retired + "  (다음 compaction 때 지운다)");
            if (swept > 0) {
                System.out.println("지웠다  지난 세대 " + swept + "개");
            }

            System.out.println("끝. base 파일 " + countFiles(fs, base) + "개");
        } finally {
            spark.stop();
        }
    }

    // ── 조각 나누기 (테스트가 여기를 본다) ──────────────────────

    /** 행 수로 정하는 파일 수. {@link #ROWS_PER_FILE} 마다 하나, 최소 1, 최대 {@link #MAX_FILES}. */
    static int plannedFiles(long rows) {
        return (int) Math.max(1, Math.min(MAX_FILES, (rows + ROWS_PER_FILE - 1) / ROWS_PER_FILE));
    }

    /** 조각 하나가 쓸 파일 수. 올림이라 전체는 {@code files} 보다 조금 많을 수 있다. */
    static int perBucket(int files, int buckets) {
        return Math.max(1, (files + buckets - 1) / buckets);
    }

    /**
     * {@code recommendationid} 의 해시로 고른 조각. 같은 리뷰(같은 id)는 늘 같은 조각에 떨어져서
     * 조각별 {@link ReviewLake#all} 은 전체를 한 번에 한 것과 같다.
     */
    static Dataset<Row> bucketOf(Dataset<Row> lake, int buckets, int bucket) {
        return lake.filter(pmod(hash(col("recommendationid")), lit(buckets)).equalTo(lit(bucket)));
    }

    static Path bucketPath(Path work, int bucket) {
        return new Path(work, "bucket-" + bucket);
    }

    static String[] bucketPaths(Path work, int buckets) {
        String[] out = new String[buckets];
        for (int b = 0; b < buckets; b++) {
            out[b] = bucketPath(work, b).toString();
        }
        return out;
    }

    /** Spark 가 잡을 끝내며 남기는 {@code _SUCCESS} 가 있으면 그 조각은 확정된 것이다. */
    static boolean bucketDone(FileSystem fs, Path bucket) {
        try {
            return fs.exists(new Path(bucket, "_SUCCESS"));
        } catch (IOException failure) {
            throw new UncheckedIOException("조각을 확인하지 못했다: " + bucket, failure);
        }
    }

    /**
     * 입력이 무엇이었는지 적어 두는 글. delta 날짜와 base 파일 이름이 하나라도 다르면 다른 입력이다.
     * 지난 실행이 남긴 조각을 이어 쓸 수 있는지는 이것이 같은지로 판단한다.
     */
    static String manifestOf(FileSystem fs, Path base, Path delta) throws IOException {
        List<String> lines = new ArrayList<>();
        if (fs.exists(delta)) {
            for (FileStatus s : fs.listStatus(delta)) {
                if (s.isDirectory() && s.getPath().getName().startsWith("dt=")) {
                    lines.add("delta/" + s.getPath().getName());
                }
            }
        }
        if (fs.exists(base)) {
            var it = fs.listFiles(base, true);
            while (it.hasNext()) {
                Path p = it.next().getPath();
                if (p.getName().endsWith(".parquet")) {
                    lines.add("base/" + p.getName());
                }
            }
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    /**
     * 조각 폴더의 parquet 파일을 {@code staging} 으로 옮긴다. 복사가 아니라 rename 이라 즉시 끝난다.
     * 조각마다 {@code part-00000-…} 이 겹칠 수 있어 조각 번호를 앞에 붙인다. {@code _SUCCESS} 는 두고 온다.
     *
     * @return 옮긴 파일 수
     */
    static int moveParquetFiles(FileSystem fs, Path work, int buckets, Path staging) throws IOException {
        fs.mkdirs(staging);
        int moved = 0;
        for (int b = 0; b < buckets; b++) {
            Path bucket = bucketPath(work, b);
            for (FileStatus s : fs.listStatus(bucket)) {
                String name = s.getPath().getName();
                if (!s.isFile() || !name.endsWith(".parquet")) {
                    continue;
                }
                Path to = new Path(staging, "b" + b + "-" + name);
                if (!fs.rename(s.getPath(), to)) {
                    throw new IOException("조각 파일을 옮기지 못했다: " + s.getPath() + " -> " + to);
                }
                moved++;
            }
        }
        return moved;
    }

    static String readText(FileSystem fs, Path file) throws IOException {
        if (!fs.exists(file)) {
            return "";
        }
        try (FSDataInputStream in = fs.open(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void writeText(FileSystem fs, Path file, String text) throws IOException {
        try (FSDataOutputStream out = fs.create(file, true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 건드리기 전에 되돌릴 지점을 만든다.
     *
     * <p>스냅샷은 복사가 아니라 바뀐 것만 기록한다(copy-on-write). {@code base} 를
     * 통째로 바꿔치기해도 용량은 실제로 달라진 만큼만 든다.
     *
     * <p>되돌리는 법
     *
     * <pre>
     *   hdfs dfs -ls /review_raw/.snapshot                              어떤 것이 있나
     *   hdfs dfs -cp -f /review_raw/.snapshot/&lt;이름&gt;/base /review_raw/   되돌리기
     * </pre>
     *
     * <p>⚠ 스냅샷이 허용돼 있지 않으면 여기서 멈춘다. 되돌릴 수단 없이 도는 것보다
     * 안 도는 편이 낫다. 정말 필요하면 {@code --no-snapshot} 을 준다.
     */
    static void takeSnapshot(FileSystem fs, Path root) throws IOException {
        String name = "before-compaction-"
                + java.time.LocalDateTime.now(com.ssafy.thispatch.common.TimeRule.ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try {
            Path taken = fs.createSnapshot(root, name);
            System.out.println("스냅샷  " + taken);
        } catch (IOException failure) {
            throw new IOException(
                    "스냅샷을 만들지 못했다. 되돌릴 수단 없이 돌리지 않는다.\n"
                            + "  한 번만 켜 주면 된다:\n"
                            + "    hdfs dfsadmin -allowSnapshot " + root + "\n"
                            + "  (정말 없이 돌리려면 --no-snapshot)", failure);
        }
        sweepOldSnapshots(fs, root);
    }

    /** 오래된 스냅샷을 {@link #KEEP_SNAPSHOTS} 개만 남기고 지운다. */
    static void sweepOldSnapshots(FileSystem fs, Path root) {
        try {
            Path dir = new Path(root, ".snapshot");
            if (!fs.exists(dir)) {
                return;
            }
            var names = new java.util.ArrayList<String>();
            for (FileStatus s : fs.listStatus(dir)) {
                if (s.getPath().getName().startsWith("before-compaction-")) {
                    names.add(s.getPath().getName());
                }
            }
            java.util.Collections.sort(names);   // 이름이 시각이라 정렬이 곧 순서다
            for (int i = 0; i < names.size() - KEEP_SNAPSHOTS; i++) {
                fs.deleteSnapshot(root, names.get(i));
                System.out.println("옛 스냅샷 지움  " + names.get(i));
            }
        } catch (IOException failure) {
            // 되돌릴 지점은 이미 만들었다. 정리에 실패했다고 멈출 이유는 없다.
            System.out.println("옛 스냅샷 정리 실패 (넘어간다): " + failure.getMessage());
        }
    }

    /**
     * 지난 세대의 {@code base.old-*} 를 지운다. 방금 만든 것은 남긴다.
     *
     * <p>행 수가 맞는데 내용이 잘못된 경우는 세는 것으로 못 잡는다. 그때 손으로
     * 되돌릴 한 벌이 필요하다.
     *
     * <p>스냅샷이 있으면 이것 없이도 되돌릴 수 있지만, <b>둘은 막는 것이 다르다</b> —
     * 스냅샷은 클러스터 안의 실수를, 이것은 스냅샷 자체가 잘못됐을 때를 막는다.
     * compaction 을 몇 번 돌려서 손실이 없는 것을 확인하면 그때 하나를 빼는 것을
     * 논의한다 (2026-09-15).
     *
     * @return 지운 개수
     */
    static int sweepOldBases(FileSystem fs, Path keep) {
        try {
            Path parent = keep.getParent();
            String prefix = new Path(HdfsPaths.REVIEW_BASE).getName() + ".old-";
            int swept = 0;
            for (FileStatus s : fs.listStatus(parent)) {
                String name = s.getPath().getName();
                if (name.startsWith(prefix) && !s.getPath().equals(keep)) {
                    if (fs.delete(s.getPath(), true)) {
                        swept++;
                    }
                }
            }
            return swept;
        } catch (IOException failure) {
            System.out.println("지난 세대 정리 실패 (넘어간다): " + failure.getMessage());
            return 0;
        }
    }

    private static FileSystem fileSystem(SparkSession spark) {
        try {
            return FileSystem.get(URI.create(HdfsPaths.HDFS),
                    spark.sparkContext().hadoopConfiguration());
        } catch (IOException failure) {
            throw new UncheckedIOException("HDFS 에 붙지 못했다", failure);
        }
    }

    /** {@code dt=} 하위 폴더 수. */
    static int countPartitions(FileSystem fs, Path root) {
        try {
            if (!fs.exists(root)) {
                return 0;
            }
            int n = 0;
            for (FileStatus s : fs.listStatus(root)) {
                if (s.isDirectory() && s.getPath().getName().startsWith("dt=")) {
                    n++;
                }
            }
            return n;
        } catch (IOException failure) {
            throw new UncheckedIOException("목록을 읽지 못했다: " + root, failure);
        }
    }

    /** 하위의 parquet 파일 수. 숨김 파일과 _SUCCESS 는 세지 않는다. */
    static int countFiles(FileSystem fs, Path root) {
        try {
            if (!fs.exists(root)) {
                return 0;
            }
            int n = 0;
            var it = fs.listFiles(root, true);
            while (it.hasNext()) {
                String name = it.next().getPath().getName();
                if (name.endsWith(".parquet")) {
                    n++;
                }
            }
            return n;
        } catch (IOException failure) {
            throw new UncheckedIOException("목록을 읽지 못했다: " + root, failure);
        }
    }

    /** 폴더 자체는 두고 안의 것만 지운다. 다음 수집이 바로 쓸 수 있게. */
    static void deleteChildren(FileSystem fs, Path root) {
        try {
            if (!fs.exists(root)) {
                return;
            }
            for (FileStatus s : fs.listStatus(root)) {
                if (!fs.delete(s.getPath(), true)) {
                    throw new IOException("지우지 못했다: " + s.getPath());
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("비우지 못했다: " + root, failure);
        }
    }
}
