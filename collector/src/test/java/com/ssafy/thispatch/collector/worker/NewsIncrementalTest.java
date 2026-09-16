package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

/**
 * 공지 증분 수집.
 *
 * <p>리뷰와 달리 <b>중간에 멈추기를 할 수 없다</b> — ISteamNews 는 한 번에 다 주고
 * 정렬도 게시일 순이라 {@code filter=updated} 같은 것이 없다. 받아서 버리는
 * 수밖에 없다. 그래서 <b>무엇을 버리고 무엇을 남기는가</b> 가 전부다.
 */
class NewsIncrementalTest {

    private static final long SINCE = 1_700_000_000L;

    private final SteamNewsClient client = mock(SteamNewsClient.class);
    private final NewsLandingWriter writer = mock(NewsLandingWriter.class);
    private final TestClock clock = new TestClock();

    private StepExecution step;
    private ChunkContext chunk;
    private NewsCollectTasklet tasklet;

    @BeforeEach
    void setUp() throws Exception {
        step = new StepExecution("collect.news:partition0", new JobExecution(1L));
        step.getExecutionContext().putString(AppidPartitioner.KEY_APPIDS, "730");
        chunk = new ChunkContext(new StepContext(step));
        // 전량 9999건 · 증분 50건으로 두고 어느 쪽을 쓰는지 본다
        tasklet = new NewsCollectTasklet(client, writer, Duration.ofSeconds(1), 2, 1,
                9999, 50, clock, clock::advance);
        when(writer.write(anyLong(), any(), any())).thenReturn(Optional.of(new Path("/n.jsonl.gz")));
        when(client.fetch(anyLong(), anyInt())).thenReturn(List.of(item("1", SINCE + 10)));
    }

    private void since(long ts) {
        step.getExecutionContext().putLong(AppidPartitioner.KEY_SINCE_TS, ts);
    }

    private int index() {
        return step.getExecutionContext().getInt(NewsCollectTasklet.KEY_APP_INDEX, 0);
    }

    @Test
    @DisplayName("전량이면 많이, 증분이면 적게 달라고 한다")
    void asksForFewerWhenIncremental() throws Exception {
        since(0L);
        tasklet.execute(step.createStepContribution(), chunk);

        verify(client).fetch(730L, 9999);
    }

    @Test
    @DisplayName("증분이면 작은 수로 요청한다")
    void incrementalUsesSmallCount() throws Exception {
        since(SINCE);
        tasklet.execute(step.createStepContribution(), chunk);

        verify(client).fetch(730L, 50);
    }

    @Test
    @DisplayName("기준보다 오래된 공지는 버린다")
    void dropsOlderThanSince() {
        List<ObjectNode> kept = NewsCollectTasklet.onlySince(
                List.of(item("new", SINCE + 100), item("old", SINCE - 100)), SINCE);

        assertEquals(1, kept.size());
        assertEquals("new", kept.get(0).path("gid").asText());
    }

    @Test
    @DisplayName("기준과 같은 시각은 남긴다 — 경계에서 놓치면 안 된다")
    void keepsExactlyAtSince() {
        assertEquals(1, NewsCollectTasklet.onlySince(List.of(item("edge", SINCE)), SINCE).size());
    }

    @Test
    @DisplayName("date 가 없는 공지는 남긴다 — 시각을 모른다고 버리면 조용히 사라진다")
    void keepsItemsWithoutDate() {
        ObjectNode noDate = new ObjectMapper().createObjectNode().put("gid", "9");

        assertEquals(1, NewsCollectTasklet.onlySince(List.of(noDate), SINCE).size());
    }

    @Test
    @DisplayName("전부 오래된 것이면 파일을 만들지 않고 다음 게임으로 간다")
    void skipsWritingWhenEverythingIsOld() throws Exception {
        since(SINCE);
        when(client.fetch(anyLong(), anyInt()))
                .thenReturn(List.of(item("old1", SINCE - 100), item("old2", SINCE - 200)));

        tasklet.execute(step.createStepContribution(), chunk);

        verify(writer, never()).write(anyLong(), any(), any());
        assertEquals(1, index(), "버렸다고 그 게임에 머무르면 안 된다");
    }

    @Test
    @DisplayName("새것이 섞여 있으면 그것만 저장한다")
    void writesOnlyTheNewOnes() throws Exception {
        since(SINCE);
        when(client.fetch(anyLong(), anyInt()))
                .thenReturn(List.of(item("new", SINCE + 50), item("old", SINCE - 50)));

        tasklet.execute(step.createStepContribution(), chunk);

        verify(writer).write(eq(730L), argThat(list ->
                list.size() == 1 && "new".equals(list.get(0).path("gid").asText())), any());
    }

    @Test
    @DisplayName("전량이면 오래된 것도 그대로 저장한다")
    void fullCollectionKeepsEverything() throws Exception {
        since(0L);
        when(client.fetch(anyLong(), anyInt()))
                .thenReturn(List.of(item("a", 100L), item("b", 200L)));

        tasklet.execute(step.createStepContribution(), chunk);

        verify(writer).write(eq(730L), argThat(list -> list.size() == 2), any());
    }

    private static ObjectNode item(String gid, long date) {
        return new ObjectMapper().createObjectNode()
                .put("gid", gid).put("title", "공지").put("contents", "[p]본문[/p]")
                .put("date", date);
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
