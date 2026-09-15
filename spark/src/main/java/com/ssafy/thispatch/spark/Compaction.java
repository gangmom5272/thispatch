package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.ReviewLake;
import com.ssafy.thispatch.common.ReviewSchema;
import com.ssafy.thispatch.common.SparkSessions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
 * </pre>
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
        for (String arg : args) {
            switch (arg == null ? "" : arg.trim()) {
                case "--dry-run" -> dryRun = true;
                // ⚠ 스냅샷 없이 돌리는 것은 되돌릴 수단을 버리는 것이다.
                //   복구는 landing 에서 전부 다시 만드는 길밖에 안 남는다.
                case "--no-snapshot" -> snapshot = false;
                default -> { }
            }
        }

        SparkSession spark = SparkSessions.build("compaction");
        try {
            FileSystem fs = fileSystem(spark);

            Path base = new Path(HdfsPaths.REVIEW_BASE);
            Path delta = new Path(HdfsPaths.REVIEW_DELTA);

            int deltaPartitions = countPartitions(fs, delta);
            if (deltaPartitions == 0) {
                System.out.println("delta 에 합칠 것이 없다. 아무것도 하지 않는다.");
                return;
            }
            System.out.println("delta 날짜 " + deltaPartitions + "개 · base 파일 "
                    + countFiles(fs, base) + "개");

            // ⚠ 읽기를 먼저 확정한다. Spark 는 게을러서, 쓰기 시점에 base 를 읽으려 든다.
            //   그런데 아래에서 base 를 바꿔치기하므로 그 전에 값을 붙들어야 한다.
            Dataset<Row> merged = ReviewLake.all(ReviewLake.read(spark)).cache();
            long rows = merged.count();
            System.out.println("합치면 " + rows + "건");

            int files = (int) Math.max(1, Math.min(MAX_FILES, (rows + ROWS_PER_FILE - 1) / ROWS_PER_FILE));
            System.out.println("파일 " + files + "개로 쓴다");

            if (dryRun) {
                System.out.println("--dry-run 이라 여기서 멈춘다.");
                return;
            }

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

            // ⚠ base 를 바로 덮어쓰면 안 된다.
            //
            //   SaveMode.Overwrite 는 쓰기 전에 그 경로를 지운다. 그런데 지금 읽는
            //   것이 base + delta 다. 자기가 읽고 있는 것을 지우는 셈이라, 운이
            //   나쁘면 base 를 통째로 잃는다.
            //
            //   그래서 옆에 새로 쓰고 이름만 바꾼다. HDFS 의 rename 은 메타데이터만
            //   건드려서 즉시 끝난다.
            String stamp = String.valueOf(System.currentTimeMillis());
            Path staging = new Path(HdfsPaths.REVIEW_BASE + ".staging-" + stamp);
            Path retired = new Path(HdfsPaths.REVIEW_BASE + ".old-" + stamp);

            System.out.println("쓴다    " + staging);
            merged.coalesce(files).write().mode(SaveMode.Overwrite).parquet(staging.toString());

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
