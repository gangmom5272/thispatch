package com.ssafy.dispatch.collector.config;

import com.ssafy.dispatch.collector.worker.CollectTasklet;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.batch.core.Step;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.integration.partition.RemotePartitioningWorkerStepBuilderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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

    /** AMQP 로 들어온 작업 요청을 채널로 흘려보낸다. */
    @Bean
    public IntegrationFlow inboundRequests(ConnectionFactory connectionFactory,
                                           DirectChannel requests,
                                           MessageConverter batchMessageConverter) {
        return IntegrationFlow
                .from(Amqp.inboundAdapter(connectionFactory, MessagingConfig.REQUESTS_QUEUE)
                        // 이걸 안 주면 어댑터가 기본 변환기를 써서 허용 목록이
                        // 적용되지 않는다. StepExecutionRequest 역직렬화가
                        // SecurityException 으로 막힌다 (2026-09-11 실제로 겪음).
                        .messageConverter(batchMessageConverter)
                        // 한 번에 하나만 집는다. 노트북 한 대가 여러 조각을 붙들면
                        // 다른 노트북이 놀게 되고, IP 를 나눈 의미가 줄어든다.
                        .configureContainer(c -> c.concurrentConsumers(1).prefetchCount(1)))
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
                                  DirectChannel requests) {
        return factory.get(STEP_NAME)
                .inputChannel(requests)
                .tasklet(new CollectTasklet(), transactionManager)
                .build();
    }
}
