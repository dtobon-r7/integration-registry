package com.rapid7.integrationregistry.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the two-tier Valkey cache. Activates {@link CacheProperties} for TTL
 * binding. The {@code StringRedisTemplate} (Lettuce) comes from Boot's {@code spring.data.redis.*}
 * auto-configuration; no custom connection factory needed.
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {}
