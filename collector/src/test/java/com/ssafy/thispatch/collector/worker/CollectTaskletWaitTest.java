package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.client.SteamReviewPage;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

/**
 * 페이지 사이 대기에서 배치 DB 를 얼마나 두드리는지 본다.
 *
 * <p><b>왜 테스트로 못 박는가.</b> 원래 코드는 대기에 들어서자마자 무조건
 * {@code jobExplorer.getJobExecution} 을 한 번 불렀다. 그 호출은 잡의 스텝을
 * 전부 끌고 온다 — 조각 234개면 약 100KB 고, 워커에서 재면 404~455ms 다
 * (2026-09-15 실측). 그게 페이지마다 나갔다.
 *
 * <p>조각을 잘게 쪼갤수록 더 나빠지므로, 무심코 되돌리면 조용히 느려진다.
 */
class CollectTaskletWaitTest {

    private final SteamReviewClient client = mock(SteamReviewClient.class);
    private final ReviewLandingWriter writer = mock(ReviewLandingWriter.class);
    private final JobExplorer explorer = mock(JobExplorer.class);
    private final TestClock clock = new TestClock();

    private StepExecution step;
    private ChunkContext chunk;
    private CollectTasklet tasklet;

    @BeforeEach
    void setUp() throws Exception {
        step = new StepExecution("collect.worker:partition0", new JobExecution(1L));
        step.getExecutionContext().putString(AppidPartitioner.KEY_APPIDS, "730,570");
        chunk = new ChunkContext(new StepContext(step));
        when(writer.writePage(anyLong(), any(), any()))
                .thenReturn(Optional.of(new Path("/saved.jsonl.gz")));
        when(client.fetchPage(anyLong(), any())).thenReturn(page());
    }

    @Test
    @DisplayName("짧은 대기에서는 배치 DB 를 두드리지 않는다")
    void shortWaitDoesNotHitTheDatabase() throws Exception {
        // 간격 1초. 대기는 1초 안에 끝나므로 확인이 끼어들 이유가 없다.
        tasklet = new CollectTasklet(client, writer, explorer,
                Duration.ofSeconds(1), 2, 1, clock, clock::advance);

        tasklet.execute(step.createStepContribution(), chunk);
        tasklet.afterChunk(chunk);

        verifyNoInteractions(explorer);
    }

    @Test
    @DisplayName("긴 백오프 중에는 여전히 확인한다 — 멈추라는 지시를 놓치면 안 된다")
    void longWaitStillChecks() throws Exception {
        // 간격을 20초로 두면 5초 주기 확인이 여러 번 걸린다.
        tasklet = new CollectTasklet(client, writer, explorer,
                Duration.ofSeconds(20), 2, 1, clock, clock::advance);

        tasklet.execute(step.createStepContribution(), chunk);
        tasklet.afterChunk(chunk);

        verify(explorer, atLeastOnce()).getJobExecution(anyLong());
        assertTrue(clock.millis() > 0);
    }

    private static SteamReviewPage page() {
        return new SteamReviewPage(
                List.of(new ObjectMapper().createObjectNode()
                        .put("recommendationid", "1").put("review", "리뷰")),
                "cursor-2");
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
