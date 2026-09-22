package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * compaction 이 파일을 다루는 방식을 본다.
 *
 * <p>Spark 를 띄우지 않고 파일 조작만 본다. 이 잡에서 데이터를 잃을 수 있는 곳은
 * 전부 여기다 — 세는 것과 지우는 것.
 */
class CompactionTest {

    private FileSystem fs;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        fs = FileSystem.getLocal(new Configuration());
        root = new Path(Files.createTempDirectory("compaction-test").toUri());
    }

    @AfterEach
    void tearDown() throws IOException {
        fs.delete(root, true);
        fs.close();
    }

    private Path touch(String relative) throws IOException {
        Path p = new Path(root, relative);
        fs.mkdirs(p.getParent());
        fs.create(p, true).close();
        return p;
    }

    @Test
    @DisplayName("dt= 폴더만 센다")
    void countsOnlyDatePartitions() throws IOException {
        Path delta = new Path(root, "delta");
        fs.mkdirs(new Path(delta, "dt=2026-09-16"));
        fs.mkdirs(new Path(delta, "dt=2026-09-17"));
        fs.mkdirs(new Path(delta, "_temporary"));   // Spark 가 남기는 것

        assertEquals(2, Compaction.countPartitions(fs, delta));
    }

    @Test
    @DisplayName("없는 경로는 0 이다 — 처음 돌 때 터지면 안 된다")
    void missingPathCountsZero() {
        assertEquals(0, Compaction.countPartitions(fs, new Path(root, "없음")));
        assertEquals(0, Compaction.countFiles(fs, new Path(root, "없음")));
    }

    @Test
    @DisplayName("parquet 만 센다 — _SUCCESS 와 숨김 파일은 빼고")
    void countsOnlyParquet() throws IOException {
        touch("base/part-0000.parquet");
        touch("base/part-0001.parquet");
        touch("base/_SUCCESS");
        touch("base/.part-0002.parquet.crc");

        assertEquals(2, Compaction.countFiles(fs, new Path(root, "base")));
    }

    @Test
    @DisplayName("delta 를 비울 때 폴더 자체는 남긴다")
    void emptiesDeltaButKeepsTheDirectory() throws IOException {
        touch("delta/dt=2026-09-16/part-0000.parquet");
        touch("delta/dt=2026-09-17/part-0000.parquet");
        Path delta = new Path(root, "delta");

        Compaction.deleteChildren(fs, delta);

        // ⚠ 폴더가 사라지면 다음 수집이 쓸 곳이 없어진다.
        assertTrue(fs.exists(delta), "delta 폴더까지 지웠다");
        assertEquals(0, fs.listStatus(delta).length);
        assertEquals(0, Compaction.countPartitions(fs, delta));
    }

    @Test
    @DisplayName("파일 수 계획 — 200만 행마다 하나, 조각 수로 올림해 나눈다")
    void plansFilesAndPerBucket() {
        assertEquals(91, Compaction.plannedFiles(181_559_518L));   // 2026-09-21 원본 행 수
        assertEquals(1, Compaction.plannedFiles(5L));
        assertEquals(200, Compaction.plannedFiles(10_000_000_000L));
        assertEquals(23, Compaction.perBucket(91, 4));
        assertEquals(1, Compaction.perBucket(1, 4));
        assertEquals(91, Compaction.perBucket(91, 1));
    }

    @Test
    @DisplayName("입력 표식은 delta 날짜·base 파일이 하나라도 달라지면 달라진다 — 그러면 이어하지 않는다")
    void manifestTracksInput() throws IOException {
        touch("base/part-a.parquet");
        fs.mkdirs(new Path(root, "delta/dt=2026-09-16"));
        Path base = new Path(root, "base");
        Path delta = new Path(root, "delta");

        String first = Compaction.manifestOf(fs, base, delta);
        assertEquals(first, Compaction.manifestOf(fs, base, delta), "같은 입력인데 표식이 다르다");

        fs.mkdirs(new Path(root, "delta/dt=2026-09-17"));   // 수집이 하루 더 쌓였다
        assertNotEquals(first, Compaction.manifestOf(fs, base, delta));

        // 첫 compaction 은 base 가 없다 — 터지지 않아야 한다
        String noBase = Compaction.manifestOf(fs, new Path(root, "없음"), delta);
        assertTrue(noBase.contains("delta/dt=2026-09-16"));
        assertFalse(noBase.contains("base/"));
    }

    @Test
    @DisplayName("조각 파일을 staging 으로 이름만 바꿔 모은다 — 겹치는 이름은 조각 번호로 가르고 _SUCCESS 는 두고 온다")
    void movesBucketFilesFlat() throws IOException {
        touch(".compaction/bucket-0/part-00000.parquet");
        touch(".compaction/bucket-0/_SUCCESS");
        touch(".compaction/bucket-1/part-00000.parquet");   // 조각마다 part 번호가 0 부터라 겹친다
        touch(".compaction/bucket-1/_SUCCESS");
        Path work = new Path(root, ".compaction");
        Path staging = new Path(root, "base.staging-1");

        assertEquals(2, Compaction.moveParquetFiles(fs, work, 2, staging));

        assertEquals(2, Compaction.countFiles(fs, staging));
        assertTrue(fs.exists(new Path(staging, "b0-part-00000.parquet")));
        assertTrue(fs.exists(new Path(staging, "b1-part-00000.parquet")));
        assertFalse(fs.exists(new Path(staging, "_SUCCESS")), "_SUCCESS 까지 옮겼다");
        assertEquals(0, Compaction.countFiles(fs, work), "복사가 아니라 이동이어야 한다");
    }

    @Test
    @DisplayName("_SUCCESS 가 있는 조각만 확정으로 본다 — 쓰다 만 조각은 다시 만든다")
    void bucketDoneNeedsSuccessMarker() throws IOException {
        Path work = new Path(root, ".compaction");
        touch(".compaction/bucket-0/part-00000.parquet");
        assertFalse(Compaction.bucketDone(fs, Compaction.bucketPath(work, 0)));
        touch(".compaction/bucket-0/_SUCCESS");
        assertTrue(Compaction.bucketDone(fs, Compaction.bucketPath(work, 0)));
        assertFalse(Compaction.bucketDone(fs, Compaction.bucketPath(work, 1)));
        assertEquals("bucket-3", Compaction.bucketPath(work, 3).getName());
        assertEquals(4, Compaction.bucketPaths(work, 4).length);
    }

    @Test
    @DisplayName("표식 글을 쓰고 읽는다 — 없으면 빈 글")
    void manifestRoundTrip() throws IOException {
        Path file = new Path(root, "_manifest");
        assertEquals("", Compaction.readText(fs, file));
        Compaction.writeText(fs, file, "delta/dt=2026-09-16\nbase/x.parquet");
        assertEquals("delta/dt=2026-09-16\nbase/x.parquet", Compaction.readText(fs, file));
    }

    @Test
    @DisplayName("rename 으로 바꿔치기하면 옛 base 가 그대로 남아 있다")
    void swapKeepsTheOldBaseUntilDeleted() throws IOException {
        touch("base/part-old.parquet");
        touch("base.staging-1/part-new.parquet");

        Path base = new Path(root, "base");
        Path retired = new Path(root, "base.old-1");
        Path staging = new Path(root, "base.staging-1");

        assertTrue(fs.rename(base, retired));
        assertTrue(fs.rename(staging, base));

        // 새 것이 base 에 올라와 있고
        assertTrue(fs.exists(new Path(base, "part-new.parquet")));
        // 옛 것은 아직 지우기 전이라 되돌릴 수 있다
        assertTrue(fs.exists(new Path(retired, "part-old.parquet")));
        assertFalse(fs.exists(staging));
    }

    @Test
    @DisplayName("지난 세대만 지우고 방금 밀어낸 base 는 남긴다")
    void keepsTheNewestRetiredBase() throws IOException {
        // 행 수는 맞는데 내용이 잘못된 경우는 세는 것으로 못 잡는다.
        // 그때 손으로 되돌릴 한 벌이 있어야 한다.
        touch("base.old-100/part.parquet");   // 지난주 것
        touch("base.old-200/part.parquet");   // 지지난주 것
        touch("base.old-300/part.parquet");   // 방금 만든 것
        Path keep = new Path(root, "base.old-300");

        int swept = Compaction.sweepOldBases(fs, keep);

        assertEquals(2, swept);
        assertTrue(fs.exists(keep), "방금 밀어낸 base 까지 지웠다");
        assertFalse(fs.exists(new Path(root, "base.old-100")));
        assertFalse(fs.exists(new Path(root, "base.old-200")));
    }

    @Test
    @DisplayName("base.old 정리가 base 나 다른 폴더를 건드리지 않는다")
    void sweepDoesNotTouchAnythingElse() throws IOException {
        touch("base/part.parquet");
        touch("delta/dt=2026-09-16/part.parquet");
        touch("base.staging-1/part.parquet");
        touch("base.old-100/part.parquet");
        Path keep = new Path(root, "base.old-999");

        Compaction.sweepOldBases(fs, keep);

        assertTrue(fs.exists(new Path(root, "base/part.parquet")), "base 를 건드렸다");
        assertTrue(fs.exists(new Path(root, "delta/dt=2026-09-16")), "delta 를 건드렸다");
        assertTrue(fs.exists(new Path(root, "base.staging-1")), "staging 을 건드렸다");
        assertFalse(fs.exists(new Path(root, "base.old-100")));
    }

    @Test
    @DisplayName("새 base 올리기에 실패하면 옛 base 를 되돌릴 수 있다")
    void canRollBackWhenPromotionFails() throws IOException {
        touch("base/part-old.parquet");
        Path base = new Path(root, "base");
        Path retired = new Path(root, "base.old-1");

        assertTrue(fs.rename(base, retired));
        // 여기서 staging 이 없어 올리기에 실패했다고 치면
        assertFalse(fs.exists(base));

        assertTrue(fs.rename(retired, base), "되돌리지 못했다");
        assertTrue(fs.exists(new Path(base, "part-old.parquet")));
    }
}
