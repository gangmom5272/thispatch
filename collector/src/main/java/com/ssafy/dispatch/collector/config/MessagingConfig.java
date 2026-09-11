package com.ssafy.dispatch.collector.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;

/**
 * 마스터와 워커가 주고받는 통로.
 *
 * <p>마스터가 "이 게임들 맡아라"를 큐에 넣고 워커가 꺼내 간다.
 * 브로커(RabbitMQ)는 마스터 노트북에 있다.
 */
@Configuration
public class MessagingConfig {

    /** 작업 요청이 지나가는 큐 이름. 마스터와 워커가 같은 값을 봐야 한다. */
    public static final String REQUESTS_QUEUE = "dispatch.collect.requests";

    @Bean
    public Queue requestsQueue() {
        // durable=true — 브로커가 재시작해도 큐가 남는다.
        // 노트북이라 껐다 켜는 일이 잦으므로 필요하다.
        return new Queue(REQUESTS_QUEUE, true);
    }

    /**
     * 작업 요청이 흐르는 채널.
     *
     * <p>마스터에서는 이 채널로 보낸 것이 AMQP 로 나가고,
     * 워커에서는 AMQP 로 들어온 것이 이 채널로 들어온다. 이름이 같아야 한다.
     */
    @Bean
    public DirectChannel requests() {
        return new DirectChannel();
    }

    /**
     * Spring Batch 가 보내는 {@code StepExecutionRequest} 를 자바 직렬화로 주고받는다.
     *
     * <p>기본 변환기는 보안상 아무 클래스나 역직렬화하지 않는다. 허용 목록을 주지
     * 않으면 워커에서 다음처럼 실패한다.
     *
     * <pre>
     *   SecurityException: Attempt to deserialize unauthorized class ...
     * </pre>
     *
     * <p>trust-all 로 열지 않고 필요한 것만 연다. 브로커가 랜 안에 있고 방화벽이
     * {@code 70.12.0.0/16} 으로 막고 있지만, 굳이 넓게 열 이유가 없다.
     */
    @Bean
    public MessageConverter batchMessageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.addAllowedListPatterns(
                "org.springframework.batch.*",
                "java.util.*",
                "java.lang.*");
        return converter;
    }
}
