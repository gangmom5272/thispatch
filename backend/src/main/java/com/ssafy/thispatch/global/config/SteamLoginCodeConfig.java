package com.ssafy.thispatch.global.config;

import java.security.SecureRandom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.thispatch.domain.member.service.SteamLoginCodeService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SteamLoginCodeProperties.class)
public class SteamLoginCodeConfig {

	@Bean
	public SteamLoginCodeService steamLoginCodeService(StringRedisTemplate redis,
		SteamLoginCodeProperties properties) {
		return new SteamLoginCodeService(redis, properties, new SecureRandom());
	}
}
