package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thispatch.collector.client.SteamNewsClient;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.NewsLandingWriter;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 공지 수집이 게임 단위로 정확히 나아가는지 본다.
 *
 * <p>리뷰와 달리 게임당 호출 한 번이라, <b>진행 위치가 한 번에 하나씩</b> 움직이는지가
 * 전부다. 여기가 어긋나면 게임을 통째로 건너뛰거나 무한히 같은 게임을 받는다.
 */
class NewsCollectTaskletTest {

    private final SteamNewsClient client = mock(SteamNewsClient.class);
    private final NewsLandingWriter writer = mock(NewsLandingWriter.class);
    private final TestClock clock = new TestClock();

    private StepExecution step;
    private ChunkContext chunk;
    private NewsCollectTasklet tasklet;

    @BeforeEach
    void setUp() throws Exception {
        step = new StepExecution("collect.news:partition0", new JobExecution(1L));
        step.getExecutionContext().putString(AppidPartitioner.KEY_APPIDS, "730,570");
        chunk = new ChunkContext(new StepContext(step));
        tasklet = new NewsCollectTasklet(client, writer, Duration.ofSeconds(1), 2, 1,
                clock, clock::advance);
        when(writer.write(anyLong(), any(), any())).thenReturn(Optional.of(new Path("/n.jsonl.gz")));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private RepeatStatus run() throws Exception {
        RepeatStatus status = tasklet.execute(step.createStepContribution(), chunk);
        tasklet.afterChunk(chunk);
        return status;
    }

    private int index() {
        return step.getExecutionContext().getInt(NewsCollectTasklet.KEY_APP_INDEX, 0);
    }

    @Test
    @DisplayName("게임 하나를 받으면 진행 위치가 하나 움직인다")
    void advancesOneGamePerCall() throws Exception {
        when(client.fetch(anyLong())).thenReturn(List.of(item("패치 1")));

        assertEquals(RepeatStatus.CONTINUABLE, run());
        assertEquals(1, index());

        assertEquals(RepeatStatus.FINISHED, run());
        assertEquals(2, index());
    }

    @Test
    @DisplayName("받은 공지를 그 게임 것으로 저장한다")
    void writesWithTheRightAppid() throws Exception {
        when(client.fetch(730L)).thenReturn(List.of(item("패치 1"), item("패치 2")));

        run();

        verify(writer).write(eq(730L), any(), any());
    }

    @Test
    @DisplayName("공지가 없는 게임은 파일을 만들지 않고 그냥 넘어간다")
    void skipsGamesWithoutNews() throws Exception {
        // 출시 전이거나 공지를 한 번도 안 올린 게임이 많다. 오류가 아니다.
        when(client.fetch(anyLong())).thenReturn(List.of());

        assertEquals(RepeatStatus.CONTINUABLE, run());

        assertEquals(1, index(), "공지가 없어도 다음 게임으로 넘어가야 한다");
        verify(writer, never()).write(anyLong(), any(), any());
    }

    @Test
    @DisplayName("저장이 실패하면 진행 위치를 옮기지 않는다")
    void keepsIndexWhenWriteFails() throws Exception {
        // ⚠ 여기가 어긋나면 안 받은 게임을 받은 것으로 치고 넘어간다. 조용히 사라진다.
        when(client.fetch(anyLong())).thenReturn(List.of(item("패치 1")));
        when(writer.write(anyLong(), any(), any())).thenThrow(new IOException("HDFS 실패"));

        assertThrows(IOException.class, this::run);
        assertEquals(0, index());
    }

    @Test
    @DisplayName("스팀이 실패하면 진행 위치를 두고 다시 시도한다")
    void retriesOnSteamFailure() throws Exception {
        when(client.fetch(anyLong())).thenThrow(new IOException("네트워크 끊김"));

        assertEquals(RepeatStatus.CONTINUABLE, run());

        assertEquals(0, index(), "실패한 게임을 건너뛰면 안 된다");
        assertEquals(1, step.getExecutionContext().getInt(NewsCollectTasklet.KEY_RETRIES, 0));
    }

    @Test
    @DisplayName("재시도를 다 쓰면 스텝을 실패시킨다")
    void failsAfterMaxRetries() throws Exception {
        when(client.fetch(anyLong())).thenThrow(new IOException("계속 실패"));

        run();   // 1
        run();   // 2
        assertThrows(IOException.class, this::run);
    }

    @Test
    @DisplayName("워커가 꺼지는 중이면 스팀을 부르지 않고 STOPPED 로 끝난다")
    void stopsCleanlyOnShutdown() throws Exception {
        ShutdownGate gate = new ShutdownGate(500L);
        gate.start();
        tasklet.setShutdownGate(gate);
        gate.stop();

        assertThrows(JobInterruptedException.class,
                () -> tasklet.execute(step.createStepContribution(), chunk));

        assertTrue(step.isTerminateOnly(), "STOPPED 가 아니면 조각이 통째로 누락된다");
        verify(client, never()).fetch(anyLong());
    }

    @Test
    @DisplayName("이어 돌릴 때 저장된 자리부터 간다")
    void resumesFromSavedIndex() throws Exception {
        step.getExecutionContext().putInt(NewsCollectTasklet.KEY_APP_INDEX, 1);
        when(client.fetch(anyLong())).thenReturn(List.of(item("패치")));

        assertEquals(RepeatStatus.FINISHED, run());

        verify(client).fetch(570L);
        verify(client, never()).fetch(730L);
    }

    private static ObjectNode item(String title) {
        return new ObjectMapper().createObjectNode()
                .put("gid", "1234").put("title", title).put("contents", "본문");
    }

    /** 잠들면 그만큼 시간이 흐른 것으로 친다. 실제로 기다리지 않는다. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
