package com.ssafy.thispatch.client.deepl;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepLProperties.class)
public class DeepLConfig {
}
