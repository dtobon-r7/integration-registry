package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Resilience tests for {@link IntegrationCache} when Valkey is unreachable (Phase 9 code-review
 * must-fix).
 *
 * <p>This test builds an {@code IntegrationCache} wired to a {@code StringRedisTemplate} pointed at
 * a closed port (no server listening) with a short command timeout to fail fast. It asserts the
 * resilience contract:
 *
 * <ul>
 *   <li>{@code readFresh(...)} returns {@code Optional.empty()} (never throws)
 *   <li>{@code readStale(...)} returns {@code Optional.empty()} (never throws)
 *   <li>{@code writeOnSuccess(...)} swallows the exception and returns normally (never throws)
 * </ul>
 *
 * <p>This is a plain unit test (no Spring context, no Testcontainers) — it verifies the {@code
 * DataAccessException} catch branches in {@link IntegrationCache} by forcing a connection failure.
 * Complement to {@link IntegrationCacheValkeyTest} (happy-path + decode failures against a real
 * Valkey).
 */
class IntegrationCacheUnavailableTest {

  private LettuceConnectionFactory connectionFactory;
  private IntegrationCache cache;
  private int deadPort;

  @BeforeEach
  void setUp() throws IOException {
    // Arrange: find a free port, immediately close it, then point Lettuce at it (classic
    // "find-then-don't-listen" — no server will be running when the test runs)
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }

    // Build a LettuceConnectionFactory with a short command timeout so failures surface fast
    RedisStandaloneConfiguration redisConfig =
        new RedisStandaloneConfiguration("127.0.0.1", deadPort);
    LettuceClientConfiguration clientConfig =
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).build();

    connectionFactory = new LettuceConnectionFactory(redisConfig, clientConfig);
    connectionFactory.afterPropertiesSet();

    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();

    // Default cache properties (5m fresh, 24h stale)
    cache = new IntegrationCache(template, new CacheProperties(null, null));
  }

  @AfterEach
  void tearDown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void readFresh_shouldReturnEmpty_whenValkeyUnreachable() {
    // Act / Assert — must not throw; a connection failure is a miss
    assertThat(cache.readFresh("org", "Product")).isEmpty();
  }

  @Test
  void readStale_shouldReturnEmpty_whenValkeyUnreachable() {
    // Act / Assert
    assertThat(cache.readStale("org", "Product")).isEmpty();
  }

  @Test
  void writeOnSuccess_shouldNotThrow_whenValkeyUnreachable() {
    // Arrange
    FetchResult result =
        CacheFetchResultFixtures.iconResult("org", Instant.parse("2026-06-01T12:00:00Z"));

    // Act / Assert — the cache must swallow the DataAccessException; a cache outage must not
    // fail an otherwise-successful fetch
    assertThatCode(() -> cache.writeOnSuccess("org", "Product", result)).doesNotThrowAnyException();
  }
}
