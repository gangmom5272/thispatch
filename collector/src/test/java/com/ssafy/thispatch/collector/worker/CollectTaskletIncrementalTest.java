package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.batch.item.ExecutionContext;

/**
 * 증분 수집에서 어디까지 받고 멈추는지 본다.
 *
 * <p><b>여기가 틀리면 리뷰가 조용히 사라진다.</b> 너무 일찍 끊으면 못 받은 것이
 * 생기고, 안 끊으면 매일 전량을 다시 받는다. 둘 다 오류가 안 난다.
 *
 * <p>기준은 게임별 워터마크가 아니라 <b>시각 하나</b>다 — 지난 성공 수집이 시작한
 * 시각에서 여유를 뺀 값. {@code filter=updated} 가 수정일 내림차순이라 그것으로
 * 충분하다.
 */
class CollectTaskletIncrementalTest {

    private static final long SINCE = 1_700_000_000L;

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
        ExecutionContext ctx = step.getExecutionContext();
        ctx.putString(AppidPartitioner.KEY_APPIDS, "730,570");
        chunk = new ChunkContext(new StepContext(step));
        tasklet = new CollectTasklet(client, writer, explorer,
                Duration.ofSeconds(1), 2, 1, clock, clock::advance);
        when(writer.writePage(anyLong(), any(), any()))
                .thenReturn(Optional.of(new Path("/saved.jsonl.gz")));
    }

    private void since(long ts) {
        step.getExecutionContext().putLong(AppidPartitioner.KEY_SINCE_TS, ts);
    }

    private int index() {
        return step.getExecutionContext().getInt(CollectTasklet.KEY_APP_INDEX, 0);
    }

    private void run() throws Exception {
        tasklet.execute(step.createStepContribution(), chunk);
        tasklet.afterChunk(chunk);
    }

    @Test
    @DisplayName("기준보다 새 수정만 있는 페이지면 계속 넘긴다")
    void keepsPagingWhileNewerThanSince() throws Exception {
        since(SINCE);
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(page("c2", SINCE + 500, SINCE + 100));

        run();

        assertEquals(0, index(), "아직 이 게임이 안 끝났는데 다음으로 넘어갔다");
    }

    @Test
    @DisplayName("기준보다 오래된 수정이 섞인 페이지에 닿으면 그 게임은 그만 받는다")
    void stopsGameWhenPageReachesSince() throws Exception {
        since(SINCE);
        // 경계 페이지 — 새것과 옛것이 섞여 있다
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(page("c2", SINCE + 100, SINCE - 1));

        run();

        assertEquals(1, index(), "기준에 닿았는데 계속 받고 있다");
    }

    @Test
    @DisplayName("경계 페이지도 저장한다 — 버리면 그 안의 새 수정까지 날아간다")
    void writesTheBoundaryPage() throws Exception {
        since(SINCE);
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(page("c2", SINCE + 100, SINCE - 1));

        run();

        verify(writer).writePage(eq(730L), any(), any());
    }

    @Test
    @DisplayName("전량 수집(기준 0)이면 오래된 리뷰에도 멈추지 않는다")
    void fullCollectionIgnoresSince() throws Exception {
        since(0L);
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(page("c2", 1000L, 100L));

        run();

        assertEquals(0, index(), "전량인데 중간에 끊었다");
    }

    @Test
    @DisplayName("다음 게임은 커서를 처음부터 시작한다")
    void resetsCursorForTheNextGame() throws Exception {
        since(SINCE);
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(page("c2", SINCE + 100, SINCE - 1));

        run();

        assertEquals(SteamReviewClient.FIRST_CURSOR,
                step.getExecutionContext().getString(CollectTasklet.KEY_CURSOR));
    }

    @Test
    @DisplayName("timestamp_updated 가 없는 리뷰 때문에 게임을 일찍 끊지 않는다")
    void missingTimestampDoesNotStopTheGame() throws Exception {
        since(SINCE);
        ObjectNode noTs = new ObjectMapper().createObjectNode()
                .put("recommendationid", "9").put("review", "시각 없음");
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(new SteamReviewPage(List.of(noTs), "c2"));

        run();

        assertEquals(0, index(), "시각을 모르는 리뷰를 옛것으로 보고 끊었다");
    }

    @Test
    @DisplayName("빈 페이지 규칙은 증분에서도 그대로 — 진짜 끝에 닿으면 넘어간다")
    void emptyPageRuleStillApplies() throws Exception {
        since(SINCE);
        when(client.fetchPage(anyLong(), any()))
                .thenReturn(new SteamReviewPage(List.of(), "c2"));

        for (int i = 0; i < 4; i++) {
            run();
        }

        assertEquals(1, index());
        verify(writer, never()).writePage(anyLong(), any(), any());
    }

    /** 수정 시각이 주어진 리뷰들로 한 페이지를 만든다. */
    private static SteamReviewPage page(String cursor, long... updatedTs) {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> reviews = new java.util.ArrayList<>();
        for (int i = 0; i < updatedTs.length; i++) {
            reviews.add(mapper.createObjectNode()
                    .put("recommendationid", String.valueOf(i))
                    .put("review", "리뷰")
                    .put("timestamp_updated", updatedTs[i]));
        }
        return new SteamReviewPage(reviews, cursor);
    }

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
