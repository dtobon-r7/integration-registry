package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@link IntegrationCache} against a real Valkey (Testcontainers, ADR-006).
 *
 * <p>Uses a minimal {@code @SpringBootTest} with auto-configuration enabled to wire up {@code
 * StringRedisTemplate} (from spring-boot-starter-data-redis), plus the cache classes under test.
 * This deliberately avoids booting the full application context (which would require a seeded
 * vendor-mapping S3 bundle + mocked S3Client + ICON base-url properties) — the cache is tested in
 * isolation. Spring Boot 4 reorganized test slices; the {@code @DataRedisTest} annotation
 * referenced in the plan does not exist, so this test uses a minimal {@code @SpringBootTest} with
 * explicit {@code @EnableAutoConfiguration} (the documented fallback).
 */
@SpringBootTest(classes = IntegrationCacheValkeyTest.TestConfig.class)
@TestPropertySource(
    properties = {
      "integration-registry.cache.fresh-ttl=1s",
      "integration-registry.cache.stale-ttl=10s",
      "management.endpoint.health.group.readiness.include="
    })
class IntegrationCacheValkeyTest extends ValkeyTestContainer {

  @Configuration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(CacheProperties.class)
  @org.springframework.context.annotation.ComponentScan(
      basePackages = "com.rapid7.integrationregistry.cache")
  static class TestConfig {
    // IntegrationCache will be discovered as a @Component via ComponentScan
  }

  @Autowired IntegrationCache cache;
  @Autowired StringRedisTemplate redis;

  private static final String ORG = "org-itest";
  private static final String PRODUCT = "InsightConnect";

  @Test
  void writeOnSuccess_shouldPopulateBothTiers_whenWritten() {
    // Arrange
    FetchResult result =
        CacheFetchResultFixtures.iconResult(ORG, Instant.parse("2026-06-01T12:00:00Z"));

    // Act
    cache.writeOnSuccess(ORG, PRODUCT, result);

    // Assert
    assertThat(cache.readFresh(ORG, PRODUCT)).contains(result);
    Optional<StaleEntry> stale = cache.readStale(ORG, PRODUCT);
    assertThat(stale).isPresent();
    assertThat(stale.get().result()).isEqualTo(result);
    assertThat(stale.get().staleSince()).isEqualTo(result.fetchedAt());
  }

  @Test
  void readFresh_shouldReturnEmpty_whenKeyAbsent() {
    // Act / Assert
    assertThat(cache.readFresh("org-absent", PRODUCT)).isEmpty();
  }

  @Test
  void readStale_shouldStillReturnEntry_whenFreshKeyManuallyEvicted() {
    // Arrange
    FetchResult result =
        CacheFetchResultFixtures.iconResult(ORG, Instant.parse("2026-06-01T12:00:00Z"));
    cache.writeOnSuccess(ORG, PRODUCT, result);

    // Act — simulate fresh-tier expiry by deleting only the fresh key; stale must survive
    redis.delete(CacheKey.of(CacheTier.FRESH, ORG, PRODUCT));

    // Assert — tiers are independent: fresh is a miss, stale is still served
    assertThat(cache.readFresh(ORG, PRODUCT)).isEmpty();
    assertThat(cache.readStale(ORG, PRODUCT)).isPresent();
  }

  @Test
  void readStale_shouldBeUnchanged_whenNoSuccessfulFetchOccurs() {
    // Arrange — a good stale entry exists
    FetchResult good =
        CacheFetchResultFixtures.iconResult(ORG, Instant.parse("2026-06-01T12:00:00Z"));
    cache.writeOnSuccess(ORG, PRODUCT, good);
    StaleEntry before = cache.readStale(ORG, PRODUCT).orElseThrow();

    // Act — no writeOnSuccess call (this is what a failed fetch does: nothing)

    // Assert — the good stale entry is observably identical
    StaleEntry after = cache.readStale(ORG, PRODUCT).orElseThrow();
    assertThat(after.result()).isEqualTo(before.result());
    assertThat(after.staleSince()).isEqualTo(before.staleSince());
  }

  @Test
  void readFresh_shouldReturnEmpty_whenStoredPayloadIsCorrupt() {
    // Arrange — a corrupt value written directly under the fresh key
    redis.opsForValue().set(CacheKey.of(CacheTier.FRESH, ORG, "corrupt"), "not a valid envelope");

    // Act / Assert — decode failure is a miss, never an exception
    assertThat(cache.readFresh(ORG, "corrupt")).isEmpty();
  }

  @Test
  void readFresh_shouldExpire_whenFreshTtlElapses() {
    // This test uses a short fresh-ttl set via @TestPropertySource
    FetchResult result = CacheFetchResultFixtures.iconResult(ORG, Instant.now());
    cache.writeOnSuccess(ORG, "ExpiryProbe", result);

    // Assert — present now, gone after the (short) fresh TTL
    assertThat(cache.readFresh(ORG, "ExpiryProbe")).isPresent();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(cache.readFresh(ORG, "ExpiryProbe")).isEmpty());
  }
}
