package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.item.ExecutionContext;

/**
 * 워커가 꺼질 때 조각이 어떻게 끝나는지 본다.
 *
 * <p><b>여기가 이 변경의 핵심이다.</b> 조각은 STOPPED 로 끝나야 한다.
 *
 * <pre>
 *   COMPLETED  안 받은 게임이 다 받은 것으로 둔갑한다  ← 절대 안 된다
 *   FAILED     이어 돌리기 전에 사람이 손을 대야 한다
 *   STOPPED    이어 돌릴 때 그대로 집어간다            ← 이것
 * </pre>
 */
class CollectTaskletShutdownTest {

    private final SteamReviewClient client = mock(SteamReviewClient.class);
    private final ReviewLandingWriter writer = mock(ReviewLandingWriter.class);
    private final JobExplorer explorer = mock(JobExplorer.class);

    private StepExecution step;
    private ChunkContext chunk;
    private CollectTasklet tasklet;
    private ShutdownGate gate;

    @BeforeEach
    void setUp() {
        step = new StepExecution("collect.worker:partition0", new JobExecution(1L));
        ExecutionContext ctx = step.getExecutionContext();
        ctx.putString(AppidPartitioner.KEY_APPIDS, "730,570");
        chunk = new ChunkContext(new StepContext(step));

        gate = new ShutdownGate(1_000L);
        gate.start();
        tasklet = new CollectTasklet(client, writer, explorer, Duration.ofSeconds(1), 2, 1);
        tasklet.setShutdownGate(gate);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("꺼지는 중이면 스팀을 더 부르지 않고 손을 뗀다")
    void stopsWithoutCallingSteam() {
        gate.stop();

        assertThrows(JobInterruptedException.class,
                () -> tasklet.execute(step.createStepContribution(), chunk));

        // 꺼지는 중에 새 요청을 보내면 그 응답을 저장할 곳이 없다
        verifyNoInteractions(client, writer);
    }

    @Test
    @DisplayName("조각이 COMPLETED 가 아니라 STOPPED 로 끝난다")
    void marksTerminateOnlyNotComplete() {
        gate.stop();

        assertThrows(JobInterruptedException.class,
                () -> tasklet.execute(step.createStepContribution(), chunk));

        // terminateOnly 가 서 있어야 Spring Batch 가 STOPPED 로 끝낸다.
        // 이게 없으면 조각이 COMPLETED 로 남아 안 받은 게임을 받은 것으로 친다.
        assertTrue(step.isTerminateOnly(), "STOPPED 로 끝나지 않는다 — 조각이 통째로 누락된다");
    }

    @Test
    @DisplayName("진행 위치는 손대지 않는다 — 직전에 커밋된 자리에서 이어진다")
    void keepsResumeIndex() {
        step.getExecutionContext().putInt(CollectTasklet.KEY_APP_INDEX, 1);
        gate.stop();

        assertThrows(JobInterruptedException.class,
                () -> tasklet.execute(step.createStepContribution(), chunk));

        assertEquals(1, step.getExecutionContext().getInt(CollectTasklet.KEY_APP_INDEX));
    }

    @Test
    @DisplayName("조각이 시작하고 끝나면 문지기가 그 수를 센다")
    void countsActivePartitions() {
        assertEquals(0, gate.activeCount());

        tasklet.beforeStep(step);
        assertEquals(1, gate.activeCount());

        tasklet.afterStep(step);
        assertEquals(0, gate.activeCount());
    }
}
