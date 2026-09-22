package com.ssafy.thispatch.domain.patch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PatchSearchProperties.class)
public class PatchSearchConfig {
}
