package com.rapid7.integrationregistry.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Test-only seam exposing cache-key internals (package-private {@link CacheKey}/{@link CacheTier})
 * to integration tests in other packages, so production cache encapsulation need not be widened.
 */
public final class CacheTestSupport {

  private CacheTestSupport() {}

  /** Delete only the FRESH-tier entry for {@code (orgId, productName)} (simulates fresh expiry). */
  public static void evictFresh(StringRedisTemplate redis, String orgId, String productName) {
    redis.delete(CacheKey.of(CacheTier.FRESH, orgId, productName));
  }
}
