package com.ssafy.thispatch.collector.worker;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 워커가 꺼질 때 하던 조각을 깨끗이 내려놓게 한다.
 *
 * <p><b>왜 필요한가.</b> 워커를 재시작하면 그때 돌고 있던 조각이 전부 실패로
 * 찍힌다. 2026-09-15 전량 수집에서 실패 86건 중 19건이 이것이었다.
 *
 * <pre>
 *   java.io.IOException: Filesystem closed                      17건
 *   java.io.InterruptedIOException: ... waiting for data ...      2건
 * </pre>
 *
 * <p>원인은 <b>닫는 순서</b>다. {@code WorkerConfig} 의 HDFS FileSystem 은
 * {@code @Bean(destroyMethod = "close")} 라서, 종료 신호가 오면 Spring 이 그것을
 * 닫는다. 그런데 그때 조각 10개가 아직 HDFS 에 쓰는 중이다. 쓰던 손 밑에서
 * 파일을 치워 버리는 셈이다.
 *
 * <p><b>무엇을 하는가.</b> 다른 것이 정리되기 전에 먼저 멈춰서
 *
 * <ol>
 *   <li>「이제 그만」 표시를 올리고 — 조각들이 다음 페이지 경계에서 알아챈다
 *   <li>돌던 조각이 전부 빠져나갈 때까지 기다린다
 * </ol>
 *
 * <p>조각은 페이지 하나를 받는 데 1~2초라 금방 빠져나간다. 빠져나간 조각은
 * {@code JobInterruptedException} 으로 끝나 <b>STOPPED</b> 가 된다. FAILED 와
 * 달리 STOPPED 는 이어 돌릴 때 그대로 집어간다.
 *
 * <p><b>⚠ 기다리는 시간에 상한을 둔다.</b> systemd 유닛의 {@code TimeoutStopSec}
 * 이 60초다. 그 안에 안 끝나면 systemd 가 SIGKILL 을 보내고, 그러면 지금과
 * 똑같이 지저분하게 죽는다. 그 전에 우리가 포기하고 남은 것을 로그에 남긴다.
 *
 * <p><b>⚠ 네트워크가 끊겨서 죽는 경우는 이걸로 못 막는다.</b> 그때는 종료 신호
 * 자체가 없다 — 워커는 멀쩡히 살아 있고 마스터에 말을 못 걸 뿐이다. 그쪽은
 * {@code 23-collect-retry.sh} 가 이어 돌려서 해결한다.
 */
public class ShutdownGate implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownGate.class);

    /** 조각이 빠져나가기를 기다리는 최대 시간. systemd 의 TimeoutStopSec 보다 짧아야 한다. */
    private final long drainTimeoutMillis;

    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean stopping;
    private volatile boolean running;

    public ShutdownGate() {
        this(45_000L);
    }

    ShutdownGate(long drainTimeoutMillis) {
        this.drainTimeoutMillis = drainTimeoutMillis;
    }

    /** 조각이 일을 시작할 때. */
    public void enter() {
        active.incrementAndGet();
    }

    /** 조각이 끝났을 때. 성공이든 실패든 반드시 불린다. */
    public void leave() {
        active.decrementAndGet();
    }

    /** 조각이 페이지 경계마다 물어본다. */
    public boolean isStopping() {
        return stopping;
    }

    int activeCount() {
        return active.get();
    }

    // ── SmartLifecycle ───────────────────────────────────────────

    /**
     * 가장 먼저 멈춘다.
     *
     * <p>Spring 은 단계(phase)가 큰 것부터 멈춘다. HDFS FileSystem 이 닫히기
     * 전에, 그리고 AMQP 리스너가 정리되기 전에 우리가 먼저 조각을 내보내야 한다.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
        stopping = true;

        int waiting = active.get();
        if (waiting == 0) {
            log.info("종료 — 돌고 있는 조각이 없다.");
            return;
        }
        log.info("종료 — 조각 {}개가 빠져나가기를 기다린다 (최대 {}초)",
                waiting, drainTimeoutMillis / 1000);

        long deadline = System.currentTimeMillis() + drainTimeoutMillis;
        while (active.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int left = active.get();
        if (left == 0) {
            log.info("조각이 전부 빠져나갔다. 이제 닫아도 된다.");
        } else {
            // 여기까지 오면 조각 left 개는 예전처럼 지저분하게 죽는다.
            // 다음에 이어 돌릴 때 23-collect-retry.sh 가 집어간다.
            log.warn("조각 {}개가 제때 안 끝났다. 그대로 닫는다 — 그 조각들은 다시 돌려야 한다.", left);
        }
    }
}
