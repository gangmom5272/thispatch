package com.ssafy.dispatch.collector.config;

import com.ssafy.dispatch.collector.partition.AppidPartitioner;
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
    @Value("${dispatch.collect.grid-size:4}")
    private int gridSize;

    // 최초 전량 수집과 403의 1시간 이상 백오프를 허용한다. -1은 제한 없음.
    @Value("${dispatch.collect.manager-timeout-ms:-1}")
    private long managerTimeoutMillis;

    /**
     * 수집 대상 게임.
     *
     * <p>지금은 뼈대 확인용으로 고정 목록이다. 실제로는 {@code game} 테이블이나
     * 스팀 카탈로그에서 가져온다 — 수집 담당이 붙인다.
     */
    @Value("${dispatch.collect.appids:2868840,1016800,413150,1245620,892970}")
    private String appidsRaw;

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
                                   DirectChannel requests) {
        List<Long> appids = Arrays.stream(appidsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();

        return factory.get("collect.manager")
                .partitioner(WorkerConfig.STEP_NAME, new AppidPartitioner(appids))
                .gridSize(gridSize)
                .outputChannel(requests)
                .pollInterval(2000)        // 2초마다 DB 를 본다
                .timeout(managerTimeoutMillis)
                .build();
    }

    @Bean
    public Job collectJob(JobRepository jobRepository, Step collectManagerStep) {
        return new JobBuilder("collectJob", jobRepository)
                .start(collectManagerStep)
                .build();
    }
}
