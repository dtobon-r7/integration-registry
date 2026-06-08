package com.rapid7.integrationregistry.cache;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers Valkey (ADR-006) for cache integration tests. Uses {@code
 * valkey/valkey:8-alpine} — the same image the live stack runs — and points {@code
 * spring.data.redis.*} at it via {@link DynamicPropertySource}.
 *
 * <p>Requires a running Docker daemon. This is the documented exception to the otherwise
 * Docker-free test suite (see TESTING.md).
 */
// Visibility widened to public so the cross-package WP-04 full-context read-path suite
// (com.rapid7.integrationregistry.integration.ReadPathTestSupport) can extend this existing
// Valkey Testcontainers base, as the WP-04 plan requires. No behavior change.
@Testcontainers
public abstract class ValkeyTestContainer {

  @Container
  static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
          .withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", VALKEY::getHost);
    registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
  }
}
