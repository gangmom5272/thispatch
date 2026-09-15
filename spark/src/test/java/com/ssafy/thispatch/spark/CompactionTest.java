package com.ssafy.thispatch.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
