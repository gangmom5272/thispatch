package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.client.SteamReviewException;
import com.ssafy.thispatch.collector.client.SteamReviewPage;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

class CollectTaskletTest {
    private final SteamReviewClient client = mock(SteamReviewClient.class);
    private final ReviewLandingWriter writer = mock(ReviewLandingWriter.class);
    private final JobExplorer explorer = mock(JobExplorer.class);
    private final MutableClock clock = new MutableClock();
    private StepExecution step;
    private ExecutionContext ctx;
    private ChunkContext chunk;
    private CollectTasklet tasklet;

    @BeforeEach
    void setUp() throws Exception {
        step = new StepExecution("collect.worker:partition0", new JobExecution(1L));
        ctx = step.getExecutionContext();
        ctx.putString(AppidPartitioner.KEY_APPIDS, "730,570");
        chunk = new ChunkContext(new StepContext(step));
        tasklet = newTasklet(clock::advance);
        when(writer.writePage(anyLong(), any(), any())).thenReturn(Optional.of(new Path("/saved.jsonl.gz")));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void savesOnePageBeforeAdvancingTheCheckpointAndCountsReviews() throws Exception {
        var page = page("next");
        when(client.fetchPage(730, "*")).thenReturn(page);
        when(writer.writePage(eq(730L), same(page), any())).thenAnswer(call -> {
            assertFalse(ctx.containsKey(CollectTasklet.KEY_CURSOR));
            assertEquals(clock.instant(), call.getArgument(2));
            return Optional.of(new Path("/saved.jsonl.gz"));
        });
        var contribution = step.createStepContribution();

        assertEquals(RepeatStatus.CONTINUABLE, tasklet.execute(contribution, chunk));

        assertEquals("next", ctx.getString(CollectTasklet.KEY_CURSOR));
        assertEquals(0, ctx.getInt(CollectTasklet.KEY_APP_INDEX));
        assertEquals(1, contribution.getReadCount());
        assertEquals(1, contribution.getWriteCount());
        verify(client, times(1)).fetchPage(anyLong(), anyString());
    }

    @Test
    void fourEmptyPagesAdvanceToTheNextAssignedGameWithTheFirstCursor() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(empty(null));
        for (int i = 0; i < 4; i++) {
            assertEquals(RepeatStatus.CONTINUABLE, executeAndWait());
        }
        assertEquals(1, ctx.getInt(CollectTasklet.KEY_APP_INDEX));
        assertEquals("*", ctx.getString(CollectTasklet.KEY_CURSOR));
        verifyNoInteractions(writer);
        when(client.fetchPage(570, "*")).thenReturn(empty(null));
        for (int i = 0; i < 3; i++) {
            assertEquals(RepeatStatus.CONTINUABLE, executeAndWait());
        }
        assertEquals(RepeatStatus.FINISHED, executeAndWait());
        verify(client, times(4)).fetchPage(730, "*");
        verify(client, times(4)).fetchPage(570, "*");
    }

    @Test
    void nonEmptyPageResetsTheConsecutiveEmptyCounter() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(empty(null), empty(null), page("next"));
        executeAndWait();
        executeAndWait();
        assertEquals(2, ctx.getInt(CollectTasklet.KEY_EMPTY_PAGES));
        executeAndWait();
        assertEquals(0, ctx.getInt(CollectTasklet.KEY_EMPTY_PAGES));
        assertEquals(0, ctx.getInt(CollectTasklet.KEY_APP_INDEX));
    }

    @Test
    void followsAnEmptyPagesCursorButRetainsItWhenTheCursorIsAbsent() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(empty("end"));
        when(client.fetchPage(730, "end")).thenReturn(empty(null));
        executeAndWait();
        executeAndWait();
        assertEquals("end", ctx.getString(CollectTasklet.KEY_CURSOR));
        assertEquals(2, ctx.getInt(CollectTasklet.KEY_EMPTY_PAGES));
    }

    @Test
    void hdfsFailureDoesNotAdvanceCursorOrCountThePageAsSaved() throws Exception {
        ctx.putString(CollectTasklet.KEY_CURSOR, "saved-cursor");
        when(client.fetchPage(730, "saved-cursor")).thenReturn(page("next"));
        when(writer.writePage(anyLong(), any(), any())).thenThrow(new IOException("HDFS unavailable"));
        var contribution = step.createStepContribution();
        assertThrows(IOException.class, () -> tasklet.execute(contribution, chunk));
        assertEquals("saved-cursor", ctx.getString(CollectTasklet.KEY_CURSOR));
        assertEquals(0, contribution.getWriteCount());
        assertFalse(ctx.containsKey(CollectTasklet.KEY_RETRIES));
    }

    @Test
    void repeatedNonEmptyCursorFailsInsteadOfLoopingForever() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(page("*"));
        assertThrows(IOException.class, () -> tasklet.execute(step.createStepContribution(), chunk));
        verifyNoInteractions(writer);
        assertFalse(ctx.containsKey(CollectTasklet.KEY_CURSOR));
    }

    @Test
    void forbiddenPersistsAnHourOfBackoffWithoutChangingTheCursor() throws Exception {
        ctx.putString(CollectTasklet.KEY_CURSOR, "saved");
        ctx.putInt(CollectTasklet.KEY_EMPTY_PAGES, 2);
        when(client.fetchPage(730, "saved")).thenThrow(httpError(403, "10"));
        long started = clock.millis();
        assertEquals(RepeatStatus.CONTINUABLE, tasklet.execute(step.createStepContribution(), chunk));
        assertEquals(started + 3_600_000, ctx.getLong(CollectTasklet.KEY_NEXT_REQUEST_AT));
        assertEquals("saved", ctx.getString(CollectTasklet.KEY_CURSOR));
        assertEquals(2, ctx.getInt(CollectTasklet.KEY_EMPTY_PAGES));
        verifyNoInteractions(writer);

        // 새 Tasklet(워커 재시작)도 영속 컨텍스트의 대기를 건너뛰지 않는다.
        tasklet = newTasklet(clock::advance);
        tasklet.execute(step.createStepContribution(), chunk);
        verify(client, times(1)).fetchPage(anyLong(), anyString());
        tasklet.afterChunk(chunk);
        assertEquals(started + 3_600_000, clock.millis());
    }

    @Test
    void rateLimitHonorsRetryAfterSeconds() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(429, "120"));
        long started = clock.millis();
        executeAndWait();
        assertEquals(started + 120_000, clock.millis());
        assertFalse(ctx.containsKey(CollectTasklet.KEY_EMPTY_PAGES));
    }

    @Test
    void forbiddenHonorsLongerRetryAfterHttpDates() throws Exception {
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                clock.instant().plusSeconds(7200).atZone(ZoneOffset.UTC));
        when(client.fetchPage(730, "*")).thenThrow(httpError(403, header));
        long started = clock.millis();
        tasklet.execute(step.createStepContribution(), chunk);
        assertEquals(started + 7_200_000, ctx.getLong(CollectTasklet.KEY_NEXT_REQUEST_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad header", "-1", "999999999999999999999999"})
    void invalidRetryAfterStillUsesTheMinimumBackoff(String header) throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(429, header));
        long started = clock.millis();
        tasklet.execute(step.createStepContribution(), chunk);
        assertEquals(started + 60_000, ctx.getLong(CollectTasklet.KEY_NEXT_REQUEST_AT));
    }

    @Test
    void transientFailuresRetryTheSameCursorWithinTheBudgetAndThenFail() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(new IOException("connection lost"));
        long started = clock.millis();
        executeAndWait();
        executeAndWait();
        assertEquals(started + 6_000, clock.millis());
        assertEquals(2, ctx.getInt(CollectTasklet.KEY_RETRIES));
        assertThrows(IOException.class, () -> tasklet.execute(step.createStepContribution(), chunk));
        verify(client, times(3)).fetchPage(730, "*");
        verifyNoInteractions(writer);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 503})
    void transientHttpStatusesCanRetry(int status) throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(status, null));
        assertEquals(RepeatStatus.CONTINUABLE, executeAndWait());
        assertEquals(1, ctx.getInt(CollectTasklet.KEY_RETRIES));
    }

    @Test
    void successResetsTheRetryBudget() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(new IOException("temporary")).thenReturn(page("next"));
        executeAndWait();
        executeAndWait();
        assertEquals(0, ctx.getInt(CollectTasklet.KEY_RETRIES));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404})
    void permanentHttpFailuresAreNotTreatedAsEmptyPages(int status) throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(status, null));
        assertThrows(SteamReviewException.class, () -> tasklet.execute(step.createStepContribution(), chunk));
        assertFalse(ctx.containsKey(CollectTasklet.KEY_EMPTY_PAGES));
        verifyNoInteractions(writer);
    }

    @Test
    void aNewPartitionUsesTheSameWorkersRequestInterval() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(page("next"));
        tasklet.execute(step.createStepContribution(), chunk);
        var other = new StepExecution("collect.worker:partition1", new JobExecution(2L));
        other.getExecutionContext().putString(AppidPartitioner.KEY_APPIDS, "570");
        var otherChunk = new ChunkContext(new StepContext(other));
        assertEquals(RepeatStatus.CONTINUABLE, tasklet.execute(other.createStepContribution(), otherChunk));
        verify(client, never()).fetchPage(eq(570L), anyString());
        tasklet.afterChunk(otherChunk);
        when(client.fetchPage(570, "*")).thenReturn(page("next"));
        tasklet.execute(other.createStepContribution(), otherChunk);
        verify(client).fetchPage(570, "*");
    }

    @Test
    void stopRequestedInTheRepositoryEndsLongBackoff() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(403, null));
        var job = new JobExecution(1L);
        job.setStatus(BatchStatus.STOPPING);
        when(explorer.getJobExecution(1L)).thenReturn(job);
        executeAndWait();
        assertTrue(step.isTerminateOnly());
        assertThrows(JobInterruptedException.class, () -> tasklet.execute(step.createStepContribution(), chunk));
    }

    @Test
    void interruptionDuringBackoffStopsTheStepAndPreservesDeadline() throws Exception {
        tasklet = newTasklet(millis -> { throw new InterruptedException(); });
        when(client.fetchPage(730, "*")).thenThrow(httpError(403, null));
        executeAndWait();
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(step.isTerminateOnly());
        assertTrue(ctx.getLong(CollectTasklet.KEY_NEXT_REQUEST_AT) > clock.millis());
    }

    @Test
    void interruptionDuringHttpDoesNotWriteOrAdvance() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(new InterruptedException());
        assertThrows(JobInterruptedException.class, () -> tasklet.execute(step.createStepContribution(), chunk));
        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(writer);
        assertFalse(ctx.containsKey(CollectTasklet.KEY_CURSOR));
    }

    @Test
    void anEmptyAssignmentFinishesWithoutRequests() throws Exception {
        ctx.putString(AppidPartitioner.KEY_APPIDS, "");
        assertEquals(RepeatStatus.FINISHED, executeAndWait());
        verifyNoInteractions(client, writer, explorer);
    }

    private RepeatStatus executeAndWait() throws Exception {
        RepeatStatus result = tasklet.execute(step.createStepContribution(), chunk);
        tasklet.afterChunk(chunk);
        return result;
    }

    private CollectTasklet newTasklet(CollectTasklet.Sleeper sleeper) {
        return new CollectTasklet(client, writer, explorer, Duration.ofSeconds(1), 2, clock, sleeper);
    }

    static SteamReviewPage page(String cursor) {
        return new SteamReviewPage(List.of(new ObjectMapper().createObjectNode()
                .put("recommendationid", "123").put("review", "한글 리뷰")), cursor);
    }

    static SteamReviewPage empty(String cursor) {
        return new SteamReviewPage(List.of(), cursor);
    }

    static SteamReviewException httpError(int status, String retryAfter) {
        return mock(SteamReviewException.class, invocation -> switch (invocation.getMethod().getName()) {
            case "kind" -> SteamReviewException.Kind.HTTP_ERROR;
            case "httpStatus" -> status;
            case "retryAfter" -> retryAfter;
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-14T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
