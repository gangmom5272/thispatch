package com.ssafy.dispatch.collector.worker;

import com.ssafy.dispatch.collector.partition.AppidPartitioner;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 워커가 자기 몫을 받아서 하는 일.
 *
 * <p><b>지금은 뼈대다.</b> 실제 수집(스팀 API 호출 · HDFS 쓰기)은 수집 담당이
 * 여기를 채운다. 지금은 "받았다"는 것만 기록해서 분배가 실제로 되는지 본다.
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
public class CollectTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CollectTasklet.class);

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext ctx = chunkContext.getStepContext()
                .getStepExecution()
                .getExecutionContext();

        int partitionNo = ctx.containsKey(AppidPartitioner.KEY_PARTITION)
                ? ctx.getInt(AppidPartitioner.KEY_PARTITION)
                : -1;
        List<Long> appids = AppidPartitioner.parse(ctx.getString(AppidPartitioner.KEY_APPIDS, ""));

        log.info("파티션 {} 을 {} 에서 받았다 — 게임 {}개 {}",
                partitionNo, whereAmI(), appids.size(), appids);

        // TODO 수집 담당이 채운다
        //   for (Long appid : appids) {
        //       스팀 appreviews 를 cursor 로 순회하며 받는다
        //       빈 페이지 4연속이면 그 게임은 끝난 것으로 본다 (실측 규칙)
        //       403 이 오면 워터마크를 유지한 채 1시간 이상 백오프한다
        //       받은 것을 .jsonl.gz 로 HdfsPaths.reviewLandingOf(dt) 에 쓴다
        //   }

        contribution.incrementReadCount();
        return RepeatStatus.FINISHED;
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
