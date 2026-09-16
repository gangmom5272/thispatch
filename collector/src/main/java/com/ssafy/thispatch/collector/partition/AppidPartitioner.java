package com.ssafy.thispatch.collector.partition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

/**
 * 수집할 게임 목록을 워커 수만큼 나눈다.
 *
 * <p>나누는 이유는 대역이 아니라 <b>IP</b> 다. 한 IP 에서 스팀에 몰아치면
 * 429 가 아니라 <b>403</b> 으로 막힌다(서버1 에서 실제로 겪었다). 그래서 호출이
 * 워커 5대의 서로 다른 IP 에서 나가야 한다.
 *
 * <p>게임을 번갈아 가며(round-robin) 나눈다. 앞에서부터 잘라 나누면 인기 게임이
 * 몰린 구간을 맡은 워커만 오래 걸린다. 리뷰 수가 게임마다 수백 배 차이 나기
 * 때문이다.
 */
public class AppidPartitioner implements Partitioner {

    /** 파티션의 ExecutionContext 에 들어가는 키. 워커가 이 이름으로 꺼낸다. */
    public static final String KEY_APPIDS = "appids";
    public static final String KEY_PARTITION = "partitionNo";

    /**
     * 이 시각(unix 초)보다 오래 고쳐진 리뷰를 만나면 그 게임은 그만 받는다.
     * 0 이면 끝까지 받는다(전량 수집).
     *
     * <p>게임마다 다른 값을 둘 이유가 없어서 <b>모든 조각에 같은 값</b>이 들어간다.
     * 자세한 것은 {@code ManagerConfig.resolveSince} 를 보라.
     */
    public static final String KEY_SINCE_TS = "sinceTs";

    private final List<Long> appids;
    private final long sinceTs;

    /** 전량 수집. 끝까지 받는다. */
    public AppidPartitioner(List<Long> appids) {
        this(appids, 0L);
    }

    public AppidPartitioner(List<Long> appids, long sinceTs) {
        this.appids = appids;
        this.sinceTs = sinceTs;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int buckets = Math.max(1, Math.min(gridSize, appids.size()));

        List<List<Long>> split = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            split.add(new ArrayList<>());
        }
        // 번갈아 담는다
        for (int i = 0; i < appids.size(); i++) {
            split.get(i % buckets).add(appids.get(i));
        }

        Map<String, ExecutionContext> result = new HashMap<>();
        for (int i = 0; i < buckets; i++) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putString(KEY_APPIDS, join(split.get(i)));
            ctx.putInt(KEY_PARTITION, i);
            ctx.putLong(KEY_SINCE_TS, sinceTs);
            // 이름은 Spring Batch 가 StepExecution 이름에 쓴다. 로그에서 구분된다.
            result.put("partition" + i, ctx);
        }
        return result;
    }

    private static String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 워커 쪽에서 되돌릴 때 쓴다. */
    public static List<Long> parse(String joined) {
        List<Long> out = new ArrayList<>();
        if (joined == null || joined.isBlank()) {
            return out;
        }
        for (String s : joined.split(",")) {
            out.add(Long.parseLong(s.trim()));
        }
        return out;
    }
}
