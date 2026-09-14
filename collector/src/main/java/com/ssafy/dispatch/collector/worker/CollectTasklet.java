package com.ssafy.dispatch.collector.worker;

import com.ssafy.dispatch.collector.client.SteamReviewClient;
import com.ssafy.dispatch.collector.client.SteamReviewException;
import com.ssafy.dispatch.collector.client.SteamReviewPage;
import com.ssafy.dispatch.collector.partition.AppidPartitioner;
import com.ssafy.dispatch.collector.writer.ReviewLandingWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 워커가 자기 몫을 받아서 하는 일.
 *
 * <p>한 번의 execute에서 한 페이지를 저장한 뒤 Spring Batch가 진행 위치를 커밋한다.
 * HDFS와 배치 DB는 단일 트랜잭션이 아니므로 저장 직후 DB 커밋 전에 죽으면 해당
 * 페이지를 다시 저장할 수 있다. 이후 Spark가 (리뷰 ID, 수정 시각) 중복을 제거한다.
 * 재시작은 동일 JobInstance의 실패/중단 실행을 재시작해야 한다.
 *
 * <p>채울 때 지켜야 할 것
 *
 * <ul>
 *   <li>스팀 API 호출은 <b>이 메서드 안에서</b> 해야 한다. 그래야 워커의 IP 로
 *       나간다. 마스터가 대신 받아다 주면 IP 를 나눈 의미가 없어진다.
 *   <li>받은 것은 <b>HDFS 에 직접</b> 쓴다. 마스터로 보내면 느린 무선 구간을
 *       두 번 탄다.
 *   <li>스팀 응답을 가공하지 않고 {@code .jsonl.gz} 로 그대로 떨군다.
 *       Parquet 변환은 Spark 잡이 한다.
 * </ul>
 */
public class CollectTasklet implements Tasklet, ChunkListener, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CollectTasklet.class);

    static final String KEY_APP_INDEX = "review.appIndex";
    static final String KEY_CURSOR = "review.cursor";
    static final String KEY_EMPTY_PAGES = "review.emptyPages";
    static final String KEY_RETRIES = "review.retries";
    static final String KEY_NEXT_REQUEST_AT = "review.nextRequestAt";
    private static final int EMPTY_PAGE_LIMIT = 4;

    private final SteamReviewClient client;
    private final ReviewLandingWriter writer;
    private final JobExplorer jobExplorer;
    private final long requestIntervalMillis;
    private final int maxRetries;
    private final Clock clock;
    private final Sleeper sleeper;
    // WorkerConfig의 소비자 1개에서 사용한다. 다음 파티션도 같은 워커의 대기를 지킨다.
    private final AtomicLong workerNextRequestAt = new AtomicLong();

    public CollectTasklet(SteamReviewClient client, ReviewLandingWriter writer, JobExplorer jobExplorer,
                          Duration requestInterval, int maxRetries) {
        this(client, writer, jobExplorer, requestInterval, maxRetries, Clock.systemUTC(), Thread::sleep);
    }

    CollectTasklet(SteamReviewClient client, ReviewLandingWriter writer, JobExplorer jobExplorer,
                   Duration requestInterval, int maxRetries, Clock clock, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client);
        this.writer = Objects.requireNonNull(writer);
        this.jobExplorer = Objects.requireNonNull(jobExplorer);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.requestIntervalMillis = Objects.requireNonNull(requestInterval).toMillis();
        if (requestIntervalMillis < 1 || maxRetries < 0) {
            throw new IllegalArgumentException("request interval must be >= 1ms and max retries >= 0");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        ExecutionContext ctx = stepExecution.getExecutionContext();
        int partitionNo = ctx.containsKey(AppidPartitioner.KEY_PARTITION)
                ? ctx.getInt(AppidPartitioner.KEY_PARTITION) : -1;
        log.info("파티션 {} 을 {} 에서 수집 — 게임 {}개, 재개 인덱스 {}",
                partitionNo, whereAmI(), appids(ctx).size(), ctx.getInt(KEY_APP_INDEX, 0));
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        checkInterrupted(step);
        ExecutionContext ctx = step.getExecutionContext();
        List<Long> appids = appids(ctx);
        int index = ctx.getInt(KEY_APP_INDEX, 0);
        if (index < 0 || index > appids.size()) {
            throw new IllegalStateException("Invalid review app index: " + index);
        }
        if (index == appids.size()) {
            return RepeatStatus.FINISHED;
        }
        // 재시작 직후에도 커밋된 백오프를 지킨다. 실제 대기는 트랜잭션 밖 afterChunk에서 한다.
        if (nextRequestAt(ctx) > clock.millis()) {
            return RepeatStatus.CONTINUABLE;
        }
        long appid = appids.get(index);
        String cursor = ctx.getString(KEY_CURSOR, SteamReviewClient.FIRST_CURSOR);
        SteamReviewPage page;
        workerNextRequestAt.set(Math.addExact(clock.millis(), requestIntervalMillis));
        try {
            page = client.fetchPage(appid, cursor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            step.setTerminateOnly();
            throw new JobInterruptedException("Interrupted while requesting Steam reviews");
        } catch (IOException e) {
            return retryOrFail(ctx, appid, e);
        }
        var collectedAt = clock.instant();
        checkInterrupted(step);
        int emptyPages = ctx.getInt(KEY_EMPTY_PAGES, 0);
        if (page.reviews().isEmpty()) {
            emptyPages++;
        } else {
            if (page.nextCursor() == null || page.nextCursor().isBlank() || cursor.equals(page.nextCursor())) {
                throw new IOException("Steam returned a non-advancing cursor for appid " + appid);
            }
            // 저장이 실패하면 아래 진행 위치를 바꾸지 않고 스텝을 실패시킨다.
            writer.writePage(appid, page, collectedAt).orElseThrow(
                    () -> new IOException("No landing file for a non-empty review page"));
            for (int i = 0; i < page.reviews().size(); i++) {
                contribution.incrementReadCount();
            }
            contribution.incrementWriteCount(page.reviews().size());
            emptyPages = 0;
        }
        if (emptyPages >= EMPTY_PAGE_LIMIT) {
            log.info("게임 {} 수집 완료 — 빈 페이지 {}회 연속", appid, emptyPages);
            index++;
            cursor = SteamReviewClient.FIRST_CURSOR;
            emptyPages = 0;
        } else if (page.nextCursor() != null) {
            cursor = page.nextCursor();
        }
        ctx.putInt(KEY_APP_INDEX, index);
        ctx.putString(KEY_CURSOR, cursor);
        ctx.putInt(KEY_EMPTY_PAGES, emptyPages);
        ctx.putInt(KEY_RETRIES, 0);
        defer(ctx, requestIntervalMillis);
        return index == appids.size() ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }

    private RepeatStatus retryOrFail(ExecutionContext ctx, long appid, IOException failure) throws IOException {
        int retries = ctx.getInt(KEY_RETRIES, 0);
        long delay;
        String retryAfter = null;
        if (failure instanceof SteamReviewException steam) {
            int status = steam.httpStatus();
            retryAfter = steam.retryAfter();
            if (steam.kind() == SteamReviewException.Kind.HTTP_ERROR && (status == 403 || status == 429)) {
                delay = status == 403 ? Duration.ofHours(1).toMillis() : Duration.ofMinutes(1).toMillis();
                defer(ctx, Math.max(delay, retryAfterMillis(retryAfter)));
                log.warn("Steam HTTP {} — 게임 {} 진행 위치 유지, {}까지 대기", status, appid,
                        java.time.Instant.ofEpochMilli(ctx.getLong(KEY_NEXT_REQUEST_AT)));
                return RepeatStatus.CONTINUABLE;
            }
            if (steam.kind() != SteamReviewException.Kind.API_FAILURE
                    && !(steam.kind() == SteamReviewException.Kind.HTTP_ERROR
                    && (status == 408 || status >= 500 && status <= 599))) {
                throw failure;
            }
        }
        if (retries >= maxRetries) {
            throw failure;
        }
        delay = Math.min(60_000L, 2_000L << Math.min(retries, 5));
        ctx.putInt(KEY_RETRIES, retries + 1);
        defer(ctx, Math.max(delay, retryAfterMillis(retryAfter)));
        log.warn("Steam 일시 오류 — 게임 {}, 재시도 {}/{}", appid, retries + 1, maxRetries);
        return RepeatStatus.CONTINUABLE;
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
        workerNextRequestAt.accumulateAndGet(deadline, Math::max);
        ctx.putLong(KEY_NEXT_REQUEST_AT, workerNextRequestAt.get());
    }

    private long nextRequestAt(ExecutionContext ctx) {
        return Math.max(workerNextRequestAt.get(), ctx.getLong(KEY_NEXT_REQUEST_AT, 0));
    }

    /** afterChunk는 진행 위치 커밋 후 호출되므로 긴 백오프 중 DB 연결을 점유하지 않는다. */
    @Override
    public void afterChunk(ChunkContext chunkContext) {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        ExecutionContext ctx = step.getExecutionContext();
        if (ctx.getInt(KEY_APP_INDEX, 0) >= appids(ctx).size()) {
            return;
        }
        long nextStopCheck = clock.millis();
        while (nextRequestAt(ctx) > clock.millis()) {
            if (step.isTerminateOnly() || Thread.currentThread().isInterrupted()) {
                step.setTerminateOnly();
                return;
            }
            if (clock.millis() >= nextStopCheck) {
                var job = jobExplorer.getJobExecution(step.getJobExecutionId());
                if (job != null && job.isStopping()) {
                    step.setTerminateOnly();
                    return;
                }
                nextStopCheck = clock.millis() + 5_000;
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

    private static List<Long> appids(ExecutionContext ctx) {
        return AppidPartitioner.parse(ctx.getString(AppidPartitioner.KEY_APPIDS, ""));
    }

    private static void checkInterrupted(StepExecution step) throws JobInterruptedException {
        if (step.isTerminateOnly() || Thread.currentThread().isInterrupted()) {
            step.setTerminateOnly();
            throw new JobInterruptedException("Review collection interrupted");
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 어느 노트북에서 돌았는지. 분배가 실제로 퍼졌는지 눈으로 확인하려고 찍는다.
     *
     * <p>{@code InetAddress.getLocalHost()} 로는 구분이 안 된다. 노트북 4대가
     * 호스트명 {@code DESKTOP-MR7IIH9} 를 공유하고, 그게 {@code /etc/hosts} 에서
     * {@code 127.0.1.1} 로 풀려서 어느 기계든 같은 값이 나온다(실측).
     *
     * <p>그래서 교육장 대역({@code 70.12.x})의 주소를 직접 찾는다.
     */
    private static String whereAmI() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    String ip = addr.getHostAddress();
                    if (ip.startsWith("70.12.")) {
                        return ip;
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "알 수 없음";
        }
    }
}
