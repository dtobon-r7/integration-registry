package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CachePropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void binding_shouldApplyConfiguredTtls_whenPropertiesPresent() {
    runner
        .withPropertyValues(
            "integration-registry.cache.fresh-ttl=10m",
            "integration-registry.cache.stale-ttl=48h")
        .run(
            context -> {
              CacheProperties props = context.getBean(CacheProperties.class);
              // Assert
              assertThat(props.freshTtl()).isEqualTo(Duration.ofMinutes(10));
              assertThat(props.staleTtl()).isEqualTo(Duration.ofHours(48));
            });
  }

  @Test
  void binding_shouldApplyRfcDefaults_whenPropertiesAbsent() {
    runner.run(
        context -> {
          CacheProperties props = context.getBean(CacheProperties.class);
          // Assert — RFC starting points
          assertThat(props.freshTtl()).isEqualTo(Duration.ofMinutes(5));
          assertThat(props.staleTtl()).isEqualTo(Duration.ofHours(24));
        });
  }

  @Configuration
  @EnableConfigurationProperties(CacheProperties.class)
  static class TestConfig {}
}
