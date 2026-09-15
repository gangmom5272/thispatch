package com.ssafy.thispatch.collector.worker;

import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.client.SteamReviewException;
import com.ssafy.thispatch.collector.client.SteamReviewPage;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
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
import org.springframework.batch.core.ExitStatus;
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

    /** 긴 백오프 중에 '잡이 멈췄나' 를 얼마 만에 한 번 확인할 것인가. */
    private static final long STOP_CHECK_INTERVAL_MILLIS = 5_000L;

    /** 몇 페이지마다 시간 배분을 로그에 남길 것인가. */
    private static final long STATS_EVERY_PAGES = 200L;

    // ── 시간이 어디로 가는지 재는 곳 ──────────────────────────────
    //
    // 2026-09-15 실측으로 조각 하나가 페이지 하나에 8.3초를 썼는데, 밖에서 잰
    // 것들(스팀 0.5초 · HDFS 0.2초 · 간격 1초 · DB 조회 0.4초)을 다 더해도
    // 3.3초밖에 안 됐다. 남은 5초를 밖에서 찾을 방법이 없어서 안에서 잰다.
    //
    // 워커 한 대의 모든 조각이 함께 더한다. 200페이지마다 한 줄 남기고 0 으로
    // 되돌린다 — 페이지마다 찍으면 로그가 그 자체로 부담이 된다.
    private final AtomicLong statPages = new AtomicLong();
    private final AtomicLong statSteamMillis = new AtomicLong();
    private final AtomicLong statWriteMillis = new AtomicLong();
    private final AtomicLong statWaitMillis = new AtomicLong();
    private final AtomicLong statStopCheckMillis = new AtomicLong();
    private final AtomicLong statFrameworkMillis = new AtomicLong();

    /**
     * 직전 청크가 끝난 시각. 조각마다 자기 스레드에서 도므로 스레드에 붙여 둔다.
     *
     * <p>이것과 다음 {@code execute} 시작 사이가 <b>우리 코드가 아닌 시간</b>이다 —
     * Spring Batch 의 트랜잭션 커밋, 컨텍스트 직렬화, 메시지 처리 같은 것들.
     * 설명 안 되던 5초가 여기 있는지 본다.
     */
    private final ThreadLocal<Long> chunkEndMillis = new ThreadLocal<>();

    private final SteamReviewClient client;
    private final ReviewLandingWriter writer;
    private final JobExplorer jobExplorer;
    private final long requestIntervalMillis;
    private final int maxRetries;
    private final Clock clock;
    private final Sleeper sleeper;
    /**
     * 이 워커가 다음 요청을 보낼 수 있는 시각. 조각들이 나눠 쓴다.
     *
     * <p>⚠ 처음에는 값이 하나였다. 조각 하나가 요청하면 나머지 전부가
     * {@code requestInterval} 만큼 막혔다. 소비자를 1 -> 10 으로 늘려도
     * 워커당 초당 1요청에 묶여 속도가 그대로였다. (2026-09-14 실측 —
     * 동시 조각 40개인데 1.85 페이지/초, 조각 하나가 페이지 하나에 21초)
     *
     * <p>그래서 '차례' 를 여러 개 둔다. 조각은 자기 차례 하나만 기다린다.
     * 워커 전체의 초당 요청 수는 {@code lanes / requestInterval} 이 된다.
     * 차례 수를 소비자 수와 맞추면 조각마다 자기 차례를 갖는 셈이다.
     */
    private final AtomicLong[] lanes;

    /** 다음 스레드에게 줄 차례 번호. 돌아가며 나눠 준다. */
    private final java.util.concurrent.atomic.AtomicInteger laneCursor =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 이 스레드가 쓰는 차례. 스레드는 큐 소비자마다 하나씩이고 오래 산다.
     *
     * <p>스레드 수와 차례 수가 같으면 정확히 하나씩 돌아간다. 컨테이너가 소비자를
     * 다시 만들면 번호가 이어지지만, 돌아가며 주므로 고르게 유지된다.
     */
    private final ThreadLocal<AtomicLong> myLane;

    /**
     * 워커가 꺼질 때 조각을 깨끗이 내려놓게 해 주는 것.
     *
     * <p>기본값은 아무 일도 안 하는 새 것이다. 실제로 쓰려면 {@code WorkerConfig}
     * 가 생명주기 빈으로 등록한 것과 <b>같은 인스턴스</b>를 넣어 줘야 한다.
     * 테스트는 그냥 두면 된다 — 종료 신호가 올 일이 없다.
     */
    private volatile ShutdownGate gate = new ShutdownGate();

    public void setShutdownGate(ShutdownGate gate) {
        this.gate = Objects.requireNonNull(gate);
    }

    public CollectTasklet(SteamReviewClient client, ReviewLandingWriter writer, JobExplorer jobExplorer,
                          Duration requestInterval, int maxRetries, int lanes) {
        this(client, writer, jobExplorer, requestInterval, maxRetries, lanes, Clock.systemUTC(), Thread::sleep);
    }

    CollectTasklet(SteamReviewClient client, ReviewLandingWriter writer, JobExplorer jobExplorer,
                   Duration requestInterval, int maxRetries, int lanes, Clock clock, Sleeper sleeper) {
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
        if (lanes < 1) {
            throw new IllegalArgumentException("lanes must be >= 1");
        }
        this.lanes = new AtomicLong[lanes];
        for (int i = 0; i < lanes; i++) {
            this.lanes[i] = new AtomicLong();
        }
        // ⚠ 필드 초기화 자리에서 만들면 안 된다. 그 시점에는 lanes 가 아직 null 이다.
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
        log.info("파티션 {} 을 {} 에서 수집 — 게임 {}개, 재개 인덱스 {}",
                partitionNo, whereAmI(), appids(ctx).size(), ctx.getInt(KEY_APP_INDEX, 0));
    }

    /**
     * 조각이 끝났다고 알린다. 성공이든 실패든 불린다.
     *
     * <p>{@link ShutdownGate} 가 이 수를 세고 있다가 0 이 되면 워커를 닫는다.
     * 여기서 빠뜨리면 종료할 때 영원히 기다리게 된다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        gate.leave();
        // ⚠ 스레드는 다음 조각이 다시 쓴다. 안 지우면 조각과 조각 사이의
        //   긴 시간이 '우리 코드 밖' 으로 잘못 더해진다.
        chunkEndMillis.remove();
        return null;   // null 이면 Spring Batch 가 원래 상태를 그대로 쓴다
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        // 워커가 꺼지는 중이면 여기서 손을 뗀다. 페이지 경계라 진행 위치는
        // 직전 청크에서 이미 커밋돼 있다 — 많아야 페이지 하나를 다시 받는다.
        //
        // ⚠ FINISHED 를 돌려주면 안 된다. 그러면 조각이 COMPLETED 로 남아
        //   아직 안 받은 게임이 다 받은 것으로 둔갑한다.
        //   setTerminateOnly 로 두면 STOPPED 가 되고, STOPPED 는 이어 돌릴 때
        //   그대로 집어간다.
        if (gate.isStopping()) {
            step.setTerminateOnly();
        }
        Long previousChunkEnd = chunkEndMillis.get();
        if (previousChunkEnd != null) {
            statFrameworkMillis.addAndGet(Math.max(0, clock.millis() - previousChunkEnd));
        }
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
        if (nextRequestAt(step, ctx) > clock.millis()) {
            return RepeatStatus.CONTINUABLE;
        }
        long appid = appids.get(index);
        String cursor = ctx.getString(KEY_CURSOR, SteamReviewClient.FIRST_CURSOR);
        SteamReviewPage page;
        // ⚠ 다음 요청이 허락되는 시각을 '지금' 기준으로 잡아 둔다.
        //
        //   전에는 이걸 잡아 두고도, 일이 다 끝난 뒤에 defer() 로 한 번 더 밀었다.
        //   그래서 한 바퀴가 '일하는 시간 + 1초' 가 됐다. 의도는 초당 한 번인데
        //   실제로는 「일 끝나고 1초 쉬기」였다.
        //
        //   2026-09-15 실측 — 일이 0.5초, 한 바퀴 1.6초. 노는 0.5초가 그대로 손해다.
        //   요청 시작부터 세면 한 바퀴가 max(일하는 시간, 1초) 가 된다.
        //   스팀에 가는 빈도는 똑같다.
        long nextSlot = Math.addExact(clock.millis(), requestIntervalMillis);
        lane(step).accumulateAndGet(nextSlot, Math::max);
        long steamStart = clock.millis();
        try {
            page = client.fetchPage(appid, cursor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            step.setTerminateOnly();
            throw new JobInterruptedException("Interrupted while requesting Steam reviews");
        } catch (IOException e) {
            statSteamMillis.addAndGet(clock.millis() - steamStart);
            return retryOrFail(step, ctx, appid, e);
        }
        statSteamMillis.addAndGet(clock.millis() - steamStart);
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
            long writeStart = clock.millis();
            writer.writePage(appid, page, collectedAt).orElseThrow(
                    () -> new IOException("No landing file for a non-empty review page"));
            statWriteMillis.addAndGet(clock.millis() - writeStart);
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
        // 요청을 보낼 때 잡아 둔 시각을 그대로 쓴다. 여기서 다시 밀지 않는다.
        ctx.putLong(KEY_NEXT_REQUEST_AT, nextSlot);
        reportStats();
        return index == appids.size() ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }

    /**
     * 페이지 하나를 세고, {@link #STATS_EVERY_PAGES} 마다 시간 배분을 한 줄 남긴다.
     *
     * <p>워커 한 대의 조각 전부를 합친 값이라 「페이지 하나에 평균 몇 초」로 읽으면
     * 된다. 남긴 뒤에는 0 으로 되돌려서 최근 구간만 보이게 한다.
     */
    private void reportStats() {
        if (statPages.incrementAndGet() % STATS_EVERY_PAGES != 0) {
            return;
        }
        long pages = STATS_EVERY_PAGES;
        long steam = statSteamMillis.getAndSet(0);
        long write = statWriteMillis.getAndSet(0);
        long wait = statWaitMillis.getAndSet(0);
        long stop = statStopCheckMillis.getAndSet(0);
        long outside = statFrameworkMillis.getAndSet(0);
        log.info("시간 배분 (페이지 {}개 평균) — 스팀 {}ms · HDFS {}ms · 대기 {}ms"
                        + " · 멈춤확인 {}ms · 우리 코드 밖 {}ms",
                pages, steam / pages, write / pages, wait / pages, stop / pages, outside / pages);
    }

    private RepeatStatus retryOrFail(StepExecution step, ExecutionContext ctx, long appid,
                                     IOException failure) throws IOException {
        int retries = ctx.getInt(KEY_RETRIES, 0);
        long delay;
        String retryAfter = null;
        if (failure instanceof SteamReviewException steam) {
            int status = steam.httpStatus();
            retryAfter = steam.retryAfter();
            if (steam.kind() == SteamReviewException.Kind.HTTP_ERROR && (status == 403 || status == 429)) {
                delay = status == 403 ? Duration.ofHours(1).toMillis() : Duration.ofMinutes(1).toMillis();
                defer(step, ctx, Math.max(delay, retryAfterMillis(retryAfter)));
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
        defer(step, ctx, Math.max(delay, retryAfterMillis(retryAfter)));
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

    private void defer(StepExecution step, ExecutionContext ctx, long delayMillis) {
        long deadline = Math.addExact(clock.millis(), Math.max(requestIntervalMillis, delayMillis));
        AtomicLong lane = lane(step);
        lane.accumulateAndGet(deadline, Math::max);
        ctx.putLong(KEY_NEXT_REQUEST_AT, lane.get());
    }

    /**
     * 이 조각이 쓸 차례.
     *
     * <p><b>스레드마다 하나씩</b> 나눠 준다. 큐 소비자 스레드 수와 차례 수가 같으므로
     * (둘 다 {@code thispatch.collect.consumers}) 조각마다 자기 차례를 갖게 된다.
     *
     * <p>⚠ 전에는 조각 이름 해시로 골랐다. 제비뽑기라 10개가 10칸에 고르게 들어가지
     * 않았다 — 2026-09-15 실측으로 조각 10개가 차례 <b>5~6개</b>만 썼다. 겹친 조각은
     * 제한을 넘겨 나가고 빈 차례는 놀았다. 제한이 의도대로 걸리지도, 처리량이 나오지도
     * 않는 상태였다.
     *
     * <pre>
     *   .106   조각 10개 → 차례 5개 (한 칸에 4개가 몰림)
     *   .76    조각  8개 → 차례 5개
     * </pre>
     */
    private AtomicLong lane(StepExecution step) {
        return myLane.get();
    }

    private long nextRequestAt(StepExecution step, ExecutionContext ctx) {
        return Math.max(lane(step).get(), ctx.getLong(KEY_NEXT_REQUEST_AT, 0));
    }

    /** afterChunk는 진행 위치 커밋 후 호출되므로 긴 백오프 중 DB 연결을 점유하지 않는다. */
    @Override
    public void afterChunk(ChunkContext chunkContext) {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        ExecutionContext ctx = step.getExecutionContext();
        if (ctx.getInt(KEY_APP_INDEX, 0) >= appids(ctx).size()) {
            return;
        }
        // ⚠ 첫 바퀴부터 확인하면 안 된다.
        //
        //   여기 있던 코드는 nextStopCheck 를 '지금' 으로 시작해서, 대기에 들어서자마자
        //   무조건 한 번 jobExplorer 를 불렀다. 보통 대기는 1초 미만이라 사실상
        //   페이지마다 한 번씩 나갔다.
        //
        //   getJobExecution 은 그 잡의 스텝을 전부 끌고 온다. 조각이 234개면 235행,
        //   약 100KB 다. 마스터에서 재면 0.24ms 인데 워커에서 재면 404~455ms 다
        //   (2026-09-15 실측) — 무선으로 그만큼을 받아오기 때문이다.
        //
        //   조각을 잘게 쪼갤수록 더 나빠진다. 1,167개면 500KB 가 된다.
        //
        //   이 확인은 '사람이 잡을 멈췄는지' 를 보려는 것이고, 긴 백오프(403 은
        //   1시간) 중에 빠져나오라고 둔 것이다. 1초짜리 대기에는 필요가 없다.
        long waitStart = clock.millis();
        long nextStopCheck = clock.millis() + STOP_CHECK_INTERVAL_MILLIS;
        while (nextRequestAt(step, ctx) > clock.millis()) {
            if (step.isTerminateOnly() || Thread.currentThread().isInterrupted()) {
                step.setTerminateOnly();
                finishChunk(waitStart);
                return;
            }
            if (clock.millis() >= nextStopCheck) {
                long t0 = clock.millis();
                var job = jobExplorer.getJobExecution(step.getJobExecutionId());
                statStopCheckMillis.addAndGet(clock.millis() - t0);
                if (job != null && job.isStopping()) {
                    step.setTerminateOnly();
                    finishChunk(waitStart);
                    return;
                }
                nextStopCheck = clock.millis() + STOP_CHECK_INTERVAL_MILLIS;
            }
            try {
                sleeper.sleep(Math.min(1_000, Math.max(1, nextRequestAt(step, ctx) - clock.millis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                step.setTerminateOnly();
                finishChunk(waitStart);
                return;
            }
        }
        finishChunk(waitStart);
    }

    /** 대기에 쓴 시간을 더하고, 청크가 끝난 시각을 남긴다. */
    private void finishChunk(long waitStart) {
        statWaitMillis.addAndGet(Math.max(0, clock.millis() - waitStart));
        chunkEndMillis.set(clock.millis());
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
