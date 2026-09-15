package com.ssafy.thispatch.collector.config;

import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
 * 공지(패치노트) 수집을 나눠 보내는 쪽. 마스터에서만 뜬다.
 *
 * <pre>
 *   java -jar collector.jar --spring.profiles.active=news-manager
 * </pre>
 *
 * <p><b>리뷰 매니저와 따로 두는 이유.</b> 한 앱에 Job 이 둘이면 어느 것을 돌릴지
 * 매번 지정해야 하고, 실수로 둘 다 뜨면 같은 워커 풀을 두 일이 나눠 쓰게 된다.
 * 프로필로 갈라서 <b>한 번에 하나만</b> 돌게 한다.
 *
 * <p>큐와 워커는 리뷰와 같은 것을 쓴다. 워커는 메시지에 적힌 스텝 이름
 * ({@link WorkerConfig#NEWS_STEP_NAME})으로 빈을 찾으므로 큐를 나눌 필요가 없다.
 *
 * <p>⚠ 리뷰 전량 수집이 도는 동안에는 돌리지 않는다. 소비자 40개를 두 일이
 * 나눠 갖게 되어 둘 다 느려진다.
 */
@Slf4j
@Configuration
@Profile("news-manager")
@EnableBatchIntegration
public class NewsManagerConfig {

    /**
     * 조각 하나에 담을 게임 수.
     *
     * <p>리뷰(100)보다 크게 잡는다. 공지는 게임당 호출 한 번이라 조각 하나가
     * 금방 끝난다 — 1,000개라도 약 17분이다. 반대로 너무 잘게 쪼개면 조각 수가
     * 늘어 배치 DB 쪽 일만 많아진다.
     */
    @Value("${thispatch.collect.news.partition-size:1000}")
    private int partitionSize;

    @Value("${thispatch.collect.grid-size:4}")
    private int gridSize;

    /** 이어 돌릴 때 처음 나눈 조각 수를 그대로 준다. 0 이면 계산한다. */
    @Value("${thispatch.collect.partitions:0}")
    private int partitionsOverride;

    @Value("${thispatch.collect.manager-timeout-ms:-1}")
    private long managerTimeoutMillis;

    @Value("${thispatch.collect.appids:}")
    private String appidsRaw;

    @Value("${thispatch.collect.service-db.url:jdbc:postgresql://127.0.0.1:15432/thispatch}")
    private String serviceDbUrl;

    @Value("${thispatch.collect.service-db.username:thispatch}")
    private String serviceDbUser;

    @Value("${thispatch.collect.service-db.password:}")
    private String serviceDbPassword;

    @Bean
    public IntegrationFlow newsOutboundRequests(AmqpTemplate amqpTemplate, DirectChannel requests) {
        return IntegrationFlow.from(requests)
                .handle(Amqp.outboundAdapter(amqpTemplate)
                        .routingKey(MessagingConfig.REQUESTS_QUEUE))
                .get();
    }

    @Bean
    public Step newsManagerStep(RemotePartitioningManagerStepBuilderFactory factory,
                                DirectChannel requests) {
        List<Long> appids = resolveAppids();

        int partitions;
        if (partitionsOverride > 0) {
            partitions = partitionsOverride;
            log.info("공지 수집 대상 {} 개 게임 · {} 조각 (조각 수를 직접 받았다 — 이어 돌리는 중)",
                    appids.size(), partitions);
        } else {
            partitions = Math.max(gridSize, (appids.size() + partitionSize - 1) / partitionSize);
            log.info("공지 수집 대상 {} 개 게임 · {} 조각 (조각당 최대 {} 개)",
                    appids.size(), partitions, partitionSize);
        }

        return factory.get("news.manager")
                .partitioner(WorkerConfig.NEWS_STEP_NAME, new AppidPartitioner(appids))
                .gridSize(partitions)
                .outputChannel(requests)
                .pollInterval(2000)
                .timeout(managerTimeoutMillis)
                .build();
    }

    /**
     * 수집 대상.
     *
     * <p>⚠ 리뷰와 기준이 다르다. 리뷰는 {@code store_review_count > 0} 인 게임만
     * 받지만(리뷰가 없으면 받을 것이 없다), 공지는 <b>리뷰가 없어도 있을 수 있다</b>.
     * 출시 직후 패치만 올린 게임이 그렇다. 그래서 게임 전체를 대상으로 한다.
     */
    private List<Long> resolveAppids() {
        if (appidsRaw != null && !appidsRaw.isBlank()) {
            List<Long> given = Arrays.stream(appidsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
            log.info("appid 를 속성에서 받았습니다 ({} 개). game 테이블을 읽지 않습니다.", given.size());
            return given;
        }
        if (serviceDbPassword == null || serviceDbPassword.isBlank()) {
            throw new IllegalStateException(
                    "서비스 DB 비밀번호가 없습니다."
                            + " thispatch.collect.service-db.password 또는 SERVICE_DB_PASSWORD 를 주세요."
                            + " (리허설이면 --thispatch.collect.appids=730,570 처럼 직접 지정해도 됩니다)");
        }
        return new GameCatalogReader(serviceDbUrl, serviceDbUser, serviceDbPassword).allAppids();
    }

    @Bean
    public Job newsJob(JobRepository jobRepository, Step newsManagerStep) {
        return new JobBuilder("newsJob", jobRepository)
                .start(newsManagerStep)
                .build();
    }
}
