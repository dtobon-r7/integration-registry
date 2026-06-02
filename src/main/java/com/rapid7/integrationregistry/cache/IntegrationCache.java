package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Two-tier Valkey-backed cache for adapter {@link FetchResult}s (ADR-005). The coordinator (T07
 * plan 02) is its only in-track caller.
 *
 * <p>Reads are total — any Valkey error or unreadable payload yields {@link Optional#empty()} (a
 * miss), never an exception. Writes happen only on a successful fetch and populate both tiers; a
 * write failure is logged and swallowed so a cache outage never fails an otherwise-good fetch. The
 * never-overwrite-good-stale invariant is structural: a failed fetch simply never calls {@link
 * #writeOnSuccess}.
 */
@Component
public class IntegrationCache {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCache.class);

  private final StringRedisTemplate redis;
  private final FetchResultCodec codec;
  private final CacheProperties properties;

  public IntegrationCache(StringRedisTemplate redis, CacheProperties properties) {
    this.redis = redis;
    this.properties = properties;
    this.codec = new FetchResultCodec();
  }

  /**
   * Fresh-tier read: a {@link FetchResult} within fresh TTL, or empty. Never returns stale data.
   */
  public Optional<FetchResult> readFresh(String orgId, String productName) {
    return read(CacheTier.FRESH, orgId, productName).flatMap(codec::decode);
  }

  /** Stale-tier read: a distinct operation the coordinator's failure path calls. */
  public Optional<StaleEntry> readStale(String orgId, String productName) {
    return read(CacheTier.STALE, orgId, productName)
        .flatMap(codec::decode)
        .map(result -> new StaleEntry(result, result.fetchedAt()));
  }

  /**
   * Write-on-success: populate fresh AND refresh stale. The only write path.
   *
   * <p>The two SETs are not atomic; a partial write (fresh written, stale not) is safe (a cache
   * miss is always valid) but degrades stale-fallback until the next successful write.
   */
  public void writeOnSuccess(String orgId, String productName, FetchResult result) {
    Objects.requireNonNull(result, "result");
    String json = codec.encode(result);
    try {
      redis
          .opsForValue()
          .set(CacheKey.of(CacheTier.FRESH, orgId, productName), json, properties.freshTtl());
      redis
          .opsForValue()
          .set(CacheKey.of(CacheTier.STALE, orgId, productName), json, properties.staleTtl());
    } catch (DataAccessException e) {
      // A cache-write failure must never fail an otherwise-successful fetch.
      log.warn("Valkey write failed for {}:{}; continuing without caching", orgId, productName, e);
    }
  }

  private Optional<String> read(CacheTier tier, String orgId, String productName) {
    try {
      return Optional.ofNullable(redis.opsForValue().get(CacheKey.of(tier, orgId, productName)));
    } catch (DataAccessException e) {
      // Valkey unreachable / timeout → a miss, never a thrown exception on the read path.
      log.debug("Valkey read failed for {} {}:{}; treating as miss", tier, orgId, productName, e);
      return Optional.empty();
    }
  }
}
