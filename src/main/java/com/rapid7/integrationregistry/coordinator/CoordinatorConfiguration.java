package com.rapid7.integrationregistry.coordinator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link CoordinatorProperties} (mirrors {@code CacheConfiguration}). */
@Configuration
@EnableConfigurationProperties(CoordinatorProperties.class)
public class CoordinatorConfiguration {}
