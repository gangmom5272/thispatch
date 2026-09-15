package com.ssafy.thispatch.collector.worker;

import com.ssafy.thispatch.collector.client.SteamNewsClient;
import com.ssafy.thispatch.collector.client.SteamNewsException;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.NewsLandingWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 워커가 맡은 게임들의 스팀 공지를 받아 HDFS 에 떨군다.
 *
 * <p><b>{@link CollectTasklet} 보다 훨씬 단순하다.</b> 리뷰는 커서로 페이지를 넘기며
 * 한 게임에 수천 번을 부르지만, 공지는 <b>게임당 한 번</b>이면 끝난다. 그래서
 * 커서도, 빈 페이지 규칙도, 게임 안에서의 재개 지점도 없다.
 *
 * <pre>
 *   리뷰   execute 한 번 = 페이지 하나   게임 하나에 수천 번
 *   공지   execute 한 번 = 게임 하나     그것으로 끝
 * </pre>
 *
 * <p>같은 것은 지킨다.
 *
 * <ul>
 *   <li>스팀 호출을 <b>여기서</b> 한다. 워커의 IP 로 나가야 한 IP 에 몰리지 않는다.
 *   <li>한 게임을 끝낼 때마다 진행 위치를 커밋한다. 죽으면 그 게임 하나만 다시 받는다.
 *   <li>요청 간격은 <b>요청 시작</b> 부터 센다. 일이 끝난 뒤부터 세면 한 바퀴가
 *       '일하는 시간 + 간격' 이 되어 그만큼 논다 (2026-09-15 에 리뷰 쪽에서 겪었다).
 *   <li>차례는 스레드마다 하나씩. 해시로 고르면 겹쳐서 절반이 놀았다 (같은 날 실측).
 * </ul>
 */
public class NewsCollectTasklet implements Tasklet, ChunkListener, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectTasklet.class);

    static final String KEY_APP_INDEX = "news.appIndex";
    static final String KEY_RETRIES = "news.retries";
    static final String KEY_NEXT_REQUEST_AT = "news.nextRequestAt";

    /** 공지가 없는 게임이 몇 개였는지 로그에 남길 주기. */
    private static final long STATS_EVERY_GAMES = 500L;

    /** 긴 백오프 중에 '잡이 멈췄나' 를 확인하는 주기. */
    private static final long STOP_CHECK_INTERVAL_MILLIS = 5_000L;

    private final SteamNewsClient client;
    private final NewsLandingWriter writer;
    private final long requestIntervalMillis;
    private final int maxRetries;
    private final Clock clock;
    private final Sleeper sleeper;
    private final AtomicLong[] lanes;
    private final AtomicInteger laneCursor = new AtomicInteger();
    private final ThreadLocal<AtomicLong> myLane;

    private final AtomicLong statGames = new AtomicLong();
    private final AtomicLong statItems = new AtomicLong();
    private final AtomicLong statEmpty = new AtomicLong();

    private volatile ShutdownGate gate = new ShutdownGate();

    public void setShutdownGate(ShutdownGate gate) {
        this.gate = Objects.requireNonNull(gate);
    }

    public NewsCollectTasklet(SteamNewsClient client, NewsLandingWriter writer,
                              Duration requestInterval, int maxRetries, int lanes) {
        this(client, writer, requestInterval, maxRetries, lanes, Clock.systemUTC(), Thread::sleep);
    }

    NewsCollectTasklet(SteamNewsClient client, NewsLandingWriter writer,
                       Duration requestInterval, int maxRetries, int lanes,
                       Clock clock, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client);
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.requestIntervalMillis = Objects.requireNonNull(requestInterval).toMillis();
        if (requestIntervalMillis < 1 || maxRetries < 0) {
            throw new IllegalArgumentException("request interval must be >= 1ms and max retries >= 0");
        }
        this.maxRetries = maxRetries;
        if (lanes < 1) {
            throw new IllegalArgumentException("lanes must be >= 1");
        }
        this.lanes = new AtomicLong[lanes];
        for (int i = 0; i < lanes; i++) {
            this.lanes[i] = new AtomicLong();
        }
        // ⚠ 필드 초기화 자리에서 만들면 lanes 가 아직 null 이다.
        AtomicLong[] built = this.lanes;
        this.myLane = ThreadLocal.withInitial(
                () -> built[Math.floorMod(laneCursor.getAndIncrement(), built.length)]);
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        gate.enter();
        ExecutionContext ctx = stepExecution.getExecutionContext();
        int partitionNo = ctx.containsKey(AppidPartitioner.KEY_PARTITION)
                ? ctx.getInt(AppidPartitioner.KEY_PARTITION) : -1;
        log.info("공지 조각 {} — 게임 {}개, 재개 인덱스 {}",
                partitionNo, appids(ctx).size(), ctx.getInt(KEY_APP_INDEX, 0));
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        gate.leave();
        return null;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        if (gate.isStopping()) {
            step.setTerminateOnly();
        }
        checkInterrupted(step);

        ExecutionContext ctx = step.getExecutionContext();
        List<Long> appids = appids(ctx);
        int index = ctx.getInt(KEY_APP_INDEX, 0);
        if (index < 0 || index > appids.size()) {
            throw new IllegalStateException("Invalid news app index: " + index);
        }
        if (index == appids.size()) {
            return RepeatStatus.FINISHED;
        }
        // 재시작 직후에도 커밋된 백오프를 지킨다. 실제 대기는 트랜잭션 밖 afterChunk 에서 한다.
        if (nextRequestAt(ctx) > clock.millis()) {
            return RepeatStatus.CONTINUABLE;
        }

        long appid = appids.get(index);
        long nextSlot = Math.addExact(clock.millis(), requestIntervalMillis);
        myLane.get().accumulateAndGet(nextSlot, Math::max);

        List<ObjectNode> items;
        try {
            items = client.fetch(appid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            step.setTerminateOnly();
            throw new JobInterruptedException("Interrupted while requesting Steam news");
        } catch (IOException e) {
            return retryOrFail(ctx, appid, e);
        }

        // ⚠ 저장이 실패하면 진행 위치를 옮기지 않는다. 그래야 다시 받는다.
        if (items.isEmpty()) {
            statEmpty.incrementAndGet();
        } else {
            writer.write(appid, items, Instant.ofEpochMilli(clock.millis()))
                    .orElseThrow(() -> new IOException("No landing file for non-empty news"));
            contribution.incrementWriteCount(items.size());
            statItems.addAndGet(items.size());
        }
        contribution.incrementReadCount();

        index++;
        ctx.putInt(KEY_APP_INDEX, index);
        ctx.putInt(KEY_RETRIES, 0);
        ctx.putLong(KEY_NEXT_REQUEST_AT, nextSlot);
        reportStats();
        return index == appids.size() ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }

    /**
     * 실패를 재시도로 넘기거나 스텝을 실패시킨다.
     *
     * <p>403 은 스팀이 막은 것이라 오래 쉰다. 그 외는 점점 늘려 가며 다시 시도한다.
     * {@link CollectTasklet} 과 같은 규칙이다.
     */
    private RepeatStatus retryOrFail(ExecutionContext ctx, long appid, IOException failure)
            throws IOException {
        int retries = ctx.getInt(KEY_RETRIES, 0);
        long delay;
        String retryAfter = null;
        if (failure instanceof SteamNewsException steam) {
            int status = steam.httpStatus();
            retryAfter = steam.retryAfter();
            if (status == 403) {
                // ⚠ 한 IP 에서 몰아치면 429 가 아니라 403 이 온다(실측). 오래 쉰다.
                delay = Duration.ofHours(1).toMillis();
            } else if (status == 429) {
                delay = Math.max(retryAfterMillis(retryAfter), Duration.ofMinutes(1).toMillis());
            } else {
                delay = backoffMillis(retries);
            }
        } else {
            delay = backoffMillis(retries);
        }

        if (retries >= maxRetries && delay < Duration.ofMinutes(1).toMillis()) {
            throw failure;
        }
        ctx.putInt(KEY_RETRIES, retries + 1);
        defer(ctx, delay);
        log.warn("게임 {} 공지 실패 ({}회) — {}초 뒤 다시. {}",
                appid, retries + 1, delay / 1000, failure.getMessage());
        return RepeatStatus.CONTINUABLE;
    }

    private long backoffMillis(int retries) {
        return Math.min(Duration.ofMinutes(5).toMillis(),
                requestIntervalMillis * (1L << Math.min(retries, 8)));
    }

    private long retryAfterMillis(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1000L));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                return Math.max(0, ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - clock.millis());
            } catch (DateTimeParseException | ArithmeticException malformed) {
                return 0;
            }
        }
    }

    private void defer(ExecutionContext ctx, long delayMillis) {
        long deadline = Math.addExact(clock.millis(), Math.max(requestIntervalMillis, delayMillis));
        AtomicLong lane = myLane.get();
        lane.accumulateAndGet(deadline, Math::max);
        ctx.putLong(KEY_NEXT_REQUEST_AT, lane.get());
    }

    private long nextRequestAt(ExecutionContext ctx) {
        return Math.max(myLane.get().get(), ctx.getLong(KEY_NEXT_REQUEST_AT, 0));
    }

    /** 진행 위치를 커밋한 뒤 기다린다. 그래야 대기 중에 DB 연결을 붙들지 않는다. */
    @Override
    public void afterChunk(ChunkContext chunkContext) {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        ExecutionContext ctx = step.getExecutionContext();
        if (ctx.getInt(KEY_APP_INDEX, 0) >= appids(ctx).size()) {
            return;
        }
        // ⚠ 첫 바퀴부터 확인하지 않는다. 리뷰 쪽에서 이것 때문에 페이지마다 무거운
        //   조회가 나가 속도가 6분의 1 이 됐다 (2026-09-15).
        long nextStopCheck = clock.millis() + STOP_CHECK_INTERVAL_MILLIS;
        while (nextRequestAt(ctx) > clock.millis()) {
            if (step.isTerminateOnly() || Thread.currentThread().isInterrupted()) {
                step.setTerminateOnly();
                return;
            }
            if (clock.millis() >= nextStopCheck) {
                if (gate.isStopping()) {
                    step.setTerminateOnly();
                    return;
                }
                nextStopCheck = clock.millis() + STOP_CHECK_INTERVAL_MILLIS;
            }
            try {
                sleeper.sleep(Math.min(1_000, Math.max(1, nextRequestAt(ctx) - clock.millis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                step.setTerminateOnly();
                return;
            }
        }
    }

    private void reportStats() {
        long games = statGames.incrementAndGet();
        if (games % STATS_EVERY_GAMES != 0) {
            return;
        }
        long items = statItems.getAndSet(0);
        long empty = statEmpty.getAndSet(0);
        log.info("공지 수집 — 게임 {}개 중 {}개는 공지 없음, 공지 {}건",
                STATS_EVERY_GAMES, empty, items);
    }

    private static List<Long> appids(ExecutionContext ctx) {
        return AppidPartitioner.parse(ctx.getString(AppidPartitioner.KEY_APPIDS, ""));
    }

    private static void checkInterrupted(StepExecution step) throws JobInterruptedException {
        if (step.isTerminateOnly() || Thread.currentThread().isInterrupted()) {
            step.setTerminateOnly();
            throw new JobInterruptedException("News collection interrupted");
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
