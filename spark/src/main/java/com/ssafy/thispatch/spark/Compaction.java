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

    public static void main(String[] args) throws IOException {
        boolean dryRun = args.length > 0 && "--dry-run".equals(args[0].trim());

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

            fs.delete(retired, true);
            System.out.println("지웠다  " + retired);

            System.out.println("끝. base 파일 " + countFiles(fs, base) + "개");
        } finally {
            spark.stop();
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
