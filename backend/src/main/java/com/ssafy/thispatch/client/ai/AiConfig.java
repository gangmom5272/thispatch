package com.ssafy.thispatch.client.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProperties.class, AiSummaryCacheProperties.class})
public class AiConfig {
}
