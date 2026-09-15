package com.ssafy.thispatch.collector.config;

import com.ssafy.thispatch.collector.worker.CollectTasklet;
import com.ssafy.thispatch.collector.worker.ShutdownGate;
import com.ssafy.thispatch.collector.client.SteamNewsClient;
import com.ssafy.thispatch.collector.worker.NewsCollectTasklet;
import com.ssafy.thispatch.collector.writer.NewsLandingWriter;
import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
import com.ssafy.thispatch.common.HdfsPaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.integration.partition.RemotePartitioningWorkerStepBuilderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 워커 노트북에서 뜨는 설정. 큐에서 자기 몫을 꺼내 수집한다.
 *
 * <pre>
 *   java -jar collector.jar --spring.profiles.active=worker
 * </pre>
 *
 * <p><b>Job 을 만들지 않는다.</b> Job 이 있으면 앱이 뜰 때 스스로 돌아버린다.
 * 워커는 마스터가 시킬 때까지 기다리기만 한다.
 *
 * <p>스팀 API 호출이 이 노트북의 IP 에서 나가는 것이 핵심이다. 한 IP 에서
 * 몰아치면 스팀이 403 으로 막는다.
 */
@Configuration
@Profile("worker")
@EnableBatchIntegration
public class WorkerConfig {

    /** 마스터의 partitioner 가 가리키는 이름과 같아야 한다. */
    public static final String STEP_NAME = "collect.worker";

    /**
     * 공지(패치노트) 수집 스텝.
     *
     * <p>큐는 리뷰와 <b>같은 것을 쓴다.</b> 워커는 메시지에 적힌 스텝 이름으로 빈을
     * 찾으므로, 이름만 다르면 한 큐로 두 가지 일을 나눠 받을 수 있다.
     *
     * <p>⚠ 둘을 동시에 돌리지 않는다. 같은 소비자 풀을 나눠 쓰게 되어 둘 다 느려진다.
     * 리뷰 전량 수집이 끝난 뒤에 공지를 돌린다.
     */
    public static final String NEWS_STEP_NAME = "collect.news";

    @Bean
    public SteamReviewClient steamReviewClient() {
        return new SteamReviewClient();
    }

    /** 워커에 설치된 Hadoop 설정을 사용한다. 이 FileSystem 인스턴스는 앱 종료 시 닫는다. */
    @Bean(destroyMethod = "close")
    public FileSystem reviewFileSystem(@Value("${thispatch.collect.hadoop-conf-dir}") String configDirectory)
            throws IOException {
        var configuration = new org.apache.hadoop.conf.Configuration();
        for (String filename : new String[] {"core-site.xml", "hdfs-site.xml"}) {
            var file = java.nio.file.Path.of(configDirectory, filename);
            if (!Files.isRegularFile(file)) {
                throw new IOException("Missing Hadoop configuration: " + file);
            }
            configuration.addResource(new Path(file.toUri()));
        }
        return FileSystem.newInstance(URI.create(HdfsPaths.HDFS), configuration);
    }

    @Bean
    public ReviewLandingWriter reviewLandingWriter(FileSystem reviewFileSystem) {
        return new ReviewLandingWriter(reviewFileSystem);
    }

    /**
     * 종료할 때 조각을 먼저 내보내는 문지기.
     *
     * <p>⚠ 이 빈이 없으면 종료 신호가 왔을 때 {@link #reviewFileSystem} 이
     * 먼저 닫히고, 그때 HDFS 에 쓰던 조각들이 {@code Filesystem closed} 로
     * 죽는다. 2026-09-15 전량 수집에서 실패 86건 중 19건이 이것이었다.
     */
    @Bean
    public ShutdownGate shutdownGate() {
        return new ShutdownGate();
    }

    @Bean
    public CollectTasklet collectTasklet(SteamReviewClient client, ReviewLandingWriter writer,
                                        JobExplorer jobExplorer,
                                        ShutdownGate shutdownGate,
                                        @Value("${thispatch.collect.request-interval}") Duration interval,
                                        @Value("${thispatch.collect.max-retries}") int maxRetries,
                                        @Value("${thispatch.collect.consumers:10}") int consumers) {
        // ⚠ 차례 수를 소비자 수와 맞춘다. 적으면 조각들이 서로를 막는다.
        CollectTasklet tasklet =
                new CollectTasklet(client, writer, jobExplorer, interval, maxRetries, consumers);
        // ⚠ 문지기와 같은 인스턴스를 써야 한다. 조각 수를 세는 쪽과 종료를
        //   기다리는 쪽이 다르면 아무도 기다리지 않는다.
        tasklet.setShutdownGate(shutdownGate);
        return tasklet;
    }

    /** AMQP 로 들어온 작업 요청을 채널로 흘려보낸다. */
    @Bean
    public IntegrationFlow inboundRequests(ConnectionFactory connectionFactory,
                                           DirectChannel requests,
                                           MessageConverter batchMessageConverter,
                                           @Value("${thispatch.collect.consumers:10}") int consumers) {
        return IntegrationFlow
                .from(Amqp.inboundAdapter(connectionFactory, MessagingConfig.REQUESTS_QUEUE)
                        // 이걸 안 주면 어댑터가 기본 변환기를 써서 허용 목록이
                        // 적용되지 않는다. StepExecutionRequest 역직렬화가
                        // SecurityException 으로 막힌다 (2026-09-11 실제로 겪음).
                        .messageConverter(batchMessageConverter)
                        // ⚠ 이 값이 전체 수집 시간을 좌우한다.
                        //
                        //   1 로 두면 노트북 한 대가 조각을 하나씩만 처리해서,
                        //   4대를 써도 스팀에 나가는 동시 요청이 4개뿐이다.
                        //   2026-09-14 실측 — 183 리뷰/초, 전량에 9.3일.
                        //
                        //   노션 「데이터 정리」 실측: 스팀은 5시간 연속 ·
                        //   동시 48개까지 막지 않았다. 문서의 2,967 리뷰/초
                        //   (전량 18시간)가 그 조건에서 나온 값이다.
                        //
                        //   ⚠ application.yaml 의 spring.rabbitmq.listener.simple.*
                        //     는 여기 적용되지 않는다. 그건 @RabbitListener 용이다.
                        //     여기서 직접 만든 컨테이너라 이 값이 최종이다.
                        //     설정만 바꾸고 이 줄을 안 고쳐서 한참 헤맸다.
                        //
                        //   prefetch 는 1 로 둔다. 미리 여러 개를 쥐면 느린 조각을
                        //   문 워커가 나머지를 붙들고 다른 노트북을 놀린다.
                        .configureContainer(c -> c.concurrentConsumers(consumers)
                                                  .maxConcurrentConsumers(consumers)
                                                  .prefetchCount(1)))
                .channel(requests)
                .get();
    }

    /**
     * 워커가 실행할 스텝.
     *
     * <p><b>빈 이름이 스텝 이름과 같아야 한다.</b> 마스터가 보낸 요청에는
     * 스텝 이름만 들어 있고, 워커는 그 이름으로 빈을 찾는다. 메서드 이름을
     * 그대로 빈 이름으로 두면 다음처럼 실패한다.
     *
     * <pre>
     *   NoSuchBeanDefinitionException: No bean named 'collect.worker' available
     * </pre>
     *
     * <p>2026-09-11 실제로 겪었다.
     */
    @Bean(name = STEP_NAME)
    public Step collectWorkerStep(RemotePartitioningWorkerStepBuilderFactory factory,
                                  PlatformTransactionManager transactionManager,
                                  DirectChannel requests,
                                  CollectTasklet collectTasklet) {
        return factory.get(STEP_NAME)
                .inputChannel(requests)
                .tasklet(collectTasklet, transactionManager)
                .listener((ChunkListener) collectTasklet)
                .build();
    }

    // ══ 공지(패치노트) 수집 ═══════════════════════════════════════

    @Bean
    public SteamNewsClient steamNewsClient() {
        return new SteamNewsClient();
    }

    @Bean
    public NewsLandingWriter newsLandingWriter(FileSystem reviewFileSystem) {
        // ⚠ FileSystem 을 리뷰와 함께 쓴다. 하나면 ShutdownGate 가 지키는 것도 하나다.
        return new NewsLandingWriter(reviewFileSystem);
    }

    @Bean
    public NewsCollectTasklet newsCollectTasklet(SteamNewsClient client, NewsLandingWriter writer,
                                                 ShutdownGate shutdownGate,
                                                 @Value("${thispatch.collect.request-interval}") Duration interval,
                                                 @Value("${thispatch.collect.max-retries}") int maxRetries,
                                                 @Value("${thispatch.collect.consumers:10}") int consumers,
                                                 @Value("${thispatch.collect.news.full-count:10000}") int fullCount,
                                                 @Value("${thispatch.collect.news.incremental-count:100}") int incrementalCount) {
        NewsCollectTasklet tasklet =
                new NewsCollectTasklet(client, writer, interval, maxRetries, consumers,
                        fullCount, incrementalCount);
        tasklet.setShutdownGate(shutdownGate);
        return tasklet;
    }

    /**
     * 공지 수집 스텝. 빈 이름이 스텝 이름과 같아야 한다 — 마스터가 보낸 요청에는
     * 이름만 들어 있고 워커는 그 이름으로 빈을 찾는다.
     */
    @Bean(name = NEWS_STEP_NAME)
    public Step newsWorkerStep(RemotePartitioningWorkerStepBuilderFactory factory,
                               PlatformTransactionManager transactionManager,
                               DirectChannel requests,
                               NewsCollectTasklet newsCollectTasklet) {
        return factory.get(NEWS_STEP_NAME)
                .inputChannel(requests)
                .tasklet(newsCollectTasklet, transactionManager)
                .listener((ChunkListener) newsCollectTasklet)
                .build();
    }
}
