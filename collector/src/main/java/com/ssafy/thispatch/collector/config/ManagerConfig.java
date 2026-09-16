package com.ssafy.thispatch.collector.config;

import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import lombok.extern.slf4j.Slf4j;
import com.ssafy.thispatch.common.TimeRule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import java.util.Arrays;
import java.util.List;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;

/**
 * 마스터에서만 뜨는 설정. 일을 나눠 보내고 끝날 때까지 지켜본다.
 *
 * <pre>
 *   java -jar collector.jar --spring.profiles.active=manager
 * </pre>
 *
 * <p>마스터는 스팀 API 를 직접 호출하지 않는다. 나누고 지켜보기만 한다.
 */
<<<<<<< HEAD
=======
@Slf4j
>>>>>>> cc38dc07f59bb4cf350cebcfb7ad9ac8bdfb0e8c
@Configuration
@Profile("manager")
@EnableBatchIntegration
public class ManagerConfig {

    /**
     * 나눌 조각 수. 워커 수와 맞춘다.
     *
     * <p>워커가 꺼져 있어도 조각은 큐에 남아 있다가 그 워커가 켜지면 처리된다.
     * 그래서 조각을 워커 수보다 많이 잡아도 되지만, 노트북이 5대뿐이라
     * 굳이 잘게 쪼개지 않는다.
     */
    @Value("${thispatch.collect.grid-size:4}")
    private int gridSize;

    // 최초 전량 수집과 403의 1시간 이상 백오프를 허용한다. -1은 제한 없음.
    @Value("${thispatch.collect.manager-timeout-ms:-1}")
    private long managerTimeoutMillis;

    /**
     * 수집 대상을 손으로 지정할 때만 쓴다. 비워 두면 {@code game} 테이블에서 읽는다.
     *
     * <pre>
     *   --thispatch.collect.appids=730,570    이 두 개만 (리허설·디버깅)
     * </pre>
     */
    @Value("${thispatch.collect.appids:}")
    private String appidsRaw;

    /**
     * 조각 하나에 담을 게임 수.
     *
     * <p>⚠ 조각 수를 워커 수(4)에 맞추면 안 된다. 그러면 조각 하나가 29,000 개
     * 게임이 되어, 하나 실패할 때 29,000 개를 다시 해야 하고 그 목록이 통째로
     * 배치 DB 의 ExecutionContext 한 행에 들어간다.
     *
     * <p>잘게 나누면 워커가 큐에서 하나씩 집어가므로, 리뷰가 많은 게임이 몰린
     * 조각을 맡은 워커가 먼저 끝낸 워커를 기다리게 하지 않는다. 부하가 저절로
     * 고르게 퍼진다. 116,618 개 기준 약 234 조각이다.
     */
    @Value("${thispatch.collect.partition-size:100}")
    private int partitionSize;

    /**
     * 조각 수를 직접 못 박는다. 0 이면 {@code partition-size} 로 계산한다.
     *
     * <p><b>실패한 조각을 이어 돌릴 때만 쓴다.</b> 이걸 안 쓰면 이어 돌리기가
     * 조용히 망가진다.
     *
     * <p>{@link AppidPartitioner} 는 게임을 <b>번갈아</b> 나눠 담고 조각 이름을
     * {@code partition0..partitionN-1} 로 붙인다. 그래서 조각 수가 달라지면
     * <b>같은 이름이 전혀 다른 게임 묶음을 뜻하게 된다.</b>
     *
     * <pre>
     *   234 조각일 때   partition0 = 게임 0, 234, 468, ...
     *   1167 조각일 때  partition0 = 게임 0, 1167, 2334, ...
     * </pre>
     *
     * <p>이 상태로 재시작하면 Spring Batch 는 이름이 같은 조각에 저장된 옛
     * 목록을 물려주고, 늘어난 {@code partition234..} 는 새로 만든다. 그 새
     * 조각들이 이미 다 받은 게임을 처음부터 다시 받는다. <b>오류는 안 난다.</b>
     *
     * <p>그래서 이어 돌릴 때는 처음 돌 때의 조각 수를 그대로 준다.
     * {@code 23-collect-retry.sh} 가 배치 DB 에서 읽어 넘긴다.
     */
    @Value("${thispatch.collect.partitions:0}")
    private int partitionsOverride;

    // ── 서비스 DB (서버1) — 게임 목록을 읽을 때만 쓴다 ──────────────
    //
    // ⚠ 이 모듈에서 서비스 DB 를 보는 곳은 여기 하나뿐이고 마스터에서만 쓴다.
    //   워커는 서버1 에 닿지 않는다. SSH 터널을 지나므로 기본값이 127.0.0.1 이다.
    @Value("${thispatch.collect.service-db.url:jdbc:postgresql://127.0.0.1:15432/thispatch}")
    private String serviceDbUrl;

    @Value("${thispatch.collect.service-db.username:thispatch}")
    private String serviceDbUser;

    @Value("${thispatch.collect.service-db.password:}")
    private String serviceDbPassword;

    /** 잡 이름. 지난 성공 실행을 찾을 때 쓴다. {@link #collectJob} 과 같아야 한다. */
    static final String JOB_NAME = "collectJob";

    /**
     * 증분으로 받을지. 끄면 지난 실행과 무관하게 끝까지 받는다.
     *
     * <pre>
     *   --thispatch.collect.incremental=false     전량으로 다시
     * </pre>
     */
    @Value("${thispatch.collect.incremental:true}")
    private boolean incremental;

    /**
     * 지난 수집 시작 시각에서 얼마를 더 거슬러 받을지.
     *
     * <p>{@code filter=updated} 정렬이 완벽하지 않다 — 1,000건에 0~1건 어긋난다
     * (노션 「데이터 정리」 실측). 겹쳐 받아서 놓치는 것을 막는다. 겹친 것은
     * {@code ReviewLake} 가 정리한다.
     */
    @Value("${thispatch.collect.slack:1h}")
    private Duration slack;

    /** 마스터가 보낸 것이 AMQP 로 나가는 통로. */
    @Bean
    public IntegrationFlow outboundRequests(AmqpTemplate amqpTemplate, DirectChannel requests) {
        return IntegrationFlow.from(requests)
                .handle(Amqp.outboundAdapter(amqpTemplate)
                        .routingKey(MessagingConfig.REQUESTS_QUEUE))
                .get();
    }

    /**
     * 나누고 기다리는 단계.
     *
     * <p>답신 큐를 따로 두지 않고 <b>JobRepository 를 들여다보며</b> 각 조각이
     * 끝났는지 확인한다({@code pollInterval}). 큐가 하나 줄어서 구성이 단순해지고,
     * 워커가 중간에 죽어도 마스터가 DB 만 보면 된다.
     */
    @Bean
    public Step collectManagerStep(RemotePartitioningManagerStepBuilderFactory factory,
                                   DirectChannel requests,
                                   JobExplorer jobExplorer) {
        List<Long> appids = resolveAppids();
        long sinceTs = resolveSince(jobExplorer);

        // 조각 수는 목록 길이에서 정한다. grid-size 는 최소치로만 쓴다 —
        // 리허설처럼 게임이 몇 개뿐일 때 조각이 0 이 되지 않게 한다.
        //
        // 다만 이어 돌리는 중이면 처음 돌 때의 조각 수를 그대로 써야 한다.
        // 그러지 않으면 같은 이름이 다른 게임 묶음을 뜻하게 된다 —
        // partitionsOverride 의 설명을 보라.
        int partitions;
        if (partitionsOverride > 0) {
            partitions = partitionsOverride;
            log.info("수집 대상 {} 개 게임 · {} 조각 (조각 수를 직접 받았다 — 이어 돌리는 중)",
                    appids.size(), partitions);
        } else {
            partitions = Math.max(gridSize, (appids.size() + partitionSize - 1) / partitionSize);
            log.info("수집 대상 {} 개 게임 · {} 조각 (조각당 최대 {} 개)",
                    appids.size(), partitions, partitionSize);
        }

        return factory.get("collect.manager")
                .partitioner(WorkerConfig.STEP_NAME, new AppidPartitioner(appids, sinceTs))
                .gridSize(partitions)
                .outputChannel(requests)
                .pollInterval(2000)        // 2초마다 DB 를 본다
                .timeout(managerTimeoutMillis)
                .build();
    }

    /**
     * 어디까지 거슬러 받을지 정한다. 0 이면 끝까지(전량 수집).
     *
     * <p><b>게임별 워터마크를 두지 않는다.</b> 처음 설계는 {@code collect_progress}
     * 테이블에 게임마다 마지막 {@code updated_ts} 를 저장하는 것이었는데, 두 가지
     * 이유로 쓰지 않는다 (2026-09-15 결정).
     *
     * <ol>
     *   <li>그 테이블이 실제 스키마에 없다. {@code batch_job} 에도 watermark 컬럼이
     *       없다. 만들려면 마이그레이션이 필요한데 백엔드가 같은 DB 를 고치는 중이다.
     *   <li><b>그럴 필요가 없다.</b> 멈추는 조건이 게임마다 다를 이유가 없다.
     * </ol>
     *
     * <p>{@code filter=updated} 는 수정일 내림차순이다(정렬 위반 1,000건에 0~1건).
     * 그러면 멈추는 조건은 시각 하나면 된다.
     *
     * <pre>
     *   updated_ts &lt; (지난번 수집이 시작한 시각 - 여유)  이면 그 게임은 그만
     * </pre>
     *
     * <p>그리고 그 시각은 Spring Batch 가 이미 들고 있다 — 따로 저장할 것이 없다.
     *
     * <p><b>⚠ 끝 시각이 아니라 시작 시각을 쓴다.</b> 수집은 몇 시간씩 걸리는데,
     * 끝 시각을 기준으로 삼으면 <b>수집이 도는 동안에 고쳐진 리뷰를 놓친다.</b>
     * 시작 시각을 쓰면 겹쳐 받게 되지만, 겹치는 것은 {@code ReviewLake} 가 정리한다.
     *
     * <p><b>⚠ COMPLETED 로 끝난 실행만 본다.</b> 조각이 실패한 채 끝난 실행을
     * 기준으로 삼으면 그때 못 받은 게임을 <b>영영 건너뛴다.</b> 오류도 안 난다.
     * {@code 23-collect-retry.sh} 가 실패 조각을 0 으로 만들어 COMPLETED 를
     * 만드는 것이 그래서 필요하다.
     */
    long resolveSince(JobExplorer jobExplorer) {
        if (!incremental) {
            log.info("전량 수집 — 끝까지 받는다 (thispatch.collect.incremental=false)");
            return 0L;
        }
        Instant lastStart = lastCompletedStart(jobExplorer);
        if (lastStart == null) {
            log.info("전량 수집 — 성공으로 끝난 지난 수집이 없다");
            return 0L;
        }
        long since = lastStart.minus(slack).getEpochSecond();
        log.info("증분 수집 — {} 이후 고쳐진 리뷰만 받는다 (지난 성공 수집 시작 {} 에서 {} 뺀 값)",
                Instant.ofEpochSecond(since), lastStart, slack);
        return since;
    }

    /** 성공으로 끝난 가장 최근 {@code collectJob} 실행의 시작 시각. 없으면 null. */
    private Instant lastCompletedStart(JobExplorer jobExplorer) {
        Instant best = null;
        // 최근 인스턴스부터 본다. 넉넉히 훑어도 하루 몇 건이라 금방 끝난다.
        for (JobInstance instance : jobExplorer.getJobInstances(JOB_NAME, 0, 50)) {
            for (JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                if (execution.getStatus() != BatchStatus.COMPLETED) {
                    continue;
                }
                Instant start = toInstant(execution.getStartTime());
                if (start != null && (best == null || start.isAfter(best))) {
                    best = start;
                }
            }
        }
        return best;
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(TimeRule.ZONE).toInstant();
    }

    /**
     * 수집 대상을 정한다. 속성으로 직접 준 것이 있으면 그것을 쓰고,
     * 없으면 서비스 DB 의 {@code game} 테이블에서 읽는다.
     */
    private List<Long> resolveAppids() {
        if (appidsRaw != null && !appidsRaw.isBlank()) {
            List<Long> given = Arrays.stream(appidsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
            log.info("appid 를 속성에서 받았습니다 ({} 개). game 테이블을 읽지 않습니다.",
                    given.size());
            return given;
        }
        if (serviceDbPassword == null || serviceDbPassword.isBlank()) {
            throw new IllegalStateException(
                    "서비스 DB 비밀번호가 없습니다."
                            + " thispatch.collect.service-db.password 또는"
                            + " SERVICE_DB_PASSWORD 를 주세요."
                            + " (리허설이면 --thispatch.collect.appids=730,570 처럼 직접 지정해도 됩니다)");
        }
        return new GameCatalogReader(serviceDbUrl, serviceDbUser, serviceDbPassword).targetAppids();
    }

    @Bean
    public Job collectJob(JobRepository jobRepository, Step collectManagerStep) {
        return new JobBuilder("collectJob", jobRepository)
                .start(collectManagerStep)
                .build();
    }
}
