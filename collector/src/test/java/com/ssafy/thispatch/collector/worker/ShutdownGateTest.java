package com.ssafy.thispatch.collector.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 종료할 때 조각을 먼저 내보내는지 본다.
 *
 * <p>이게 틀리면 HDFS FileSystem 이 먼저 닫혀서 쓰던 조각들이
 * {@code Filesystem closed} 로 죽는다 — 2026-09-15 에 19건 났다.
 */
class ShutdownGateTest {

    @Test
    @DisplayName("멈추기 전에는 조각이 계속 일한다")
    void notStoppingBeforeStop() {
        ShutdownGate gate = new ShutdownGate();
        gate.start();

        assertFalse(gate.isStopping());
        assertTrue(gate.isRunning());
    }

    @Test
    @DisplayName("돌고 있는 조각이 없으면 기다리지 않는다")
    void stopsImmediatelyWhenIdle() {
        ShutdownGate gate = new ShutdownGate(5_000L);
        gate.start();

        long before = System.currentTimeMillis();
        gate.stop();
        long spent = System.currentTimeMillis() - before;

        assertTrue(gate.isStopping());
        assertFalse(gate.isRunning());
        assertTrue(spent < 1_000, "기다릴 것이 없는데 " + spent + "ms 를 썼다");
    }

    @Test
    @DisplayName("조각이 빠져나갈 때까지 기다렸다가 닫는다")
    void waitsForActivePartitions() throws Exception {
        ShutdownGate gate = new ShutdownGate(5_000L);
        gate.start();
        gate.enter();
        assertEquals(1, gate.activeCount());

        CountDownLatch stopped = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            gate.stop();
            stopped.countDown();
        });
        closer.start();

        // 조각이 아직 남아 있으므로 닫히면 안 된다
        assertFalse(stopped.await(300, TimeUnit.MILLISECONDS), "조각이 남았는데 닫아 버렸다");
        // 조각이 「이제 그만」을 보고 빠져나간다
        assertTrue(gate.isStopping(), "조각에게 알리지 않았다");
        gate.leave();

        assertTrue(stopped.await(3, TimeUnit.SECONDS), "조각이 다 빠졌는데도 안 닫혔다");
        assertEquals(0, gate.activeCount());
        closer.join(1_000);
    }

    @Test
    @DisplayName("조각이 제때 안 끝나면 정해진 시간만 기다리고 닫는다")
    void givesUpAfterTimeout() {
        // systemd 가 TimeoutStopSec 뒤에 SIGKILL 을 보낸다. 그 전에 우리가 포기해야
        // 로그라도 남는다.
        ShutdownGate gate = new ShutdownGate(300L);
        gate.start();
        gate.enter();   // 영영 안 끝나는 조각

        long before = System.currentTimeMillis();
        gate.stop();
        long spent = System.currentTimeMillis() - before;

        assertTrue(spent >= 300, "기다리는 시늉만 했다 (" + spent + "ms)");
        assertTrue(spent < 3_000, "너무 오래 기다렸다 (" + spent + "ms)");
        assertEquals(1, gate.activeCount());
    }

    @Test
    @DisplayName("가장 먼저 멈춘다 — HDFS 가 닫히기 전이어야 한다")
    void stopsBeforeEverythingElse() {
        // Spring 은 단계가 큰 것부터 멈춘다.
        assertEquals(Integer.MAX_VALUE, new ShutdownGate().getPhase());
    }
}
