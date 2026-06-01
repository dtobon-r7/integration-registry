package com.rapid7.integrationregistry.cache;

/**
 * The single Valkey-key construction site for the cache. Keys are {@code
 * ir:cache:{tier}:{orgId}:{productName}}.
 *
 * <p>This is deliberately the only place a cache key is built: the two tiers stay independent
 * because their keys differ by the {@code tier} segment, and a future change to a user-scoped key —
 * {@code (orgId, userId, productName)} — touches only this method and its direct callers.
 *
 * <p>Assumption: {@code orgId} and {@code productName} are platform-controlled values that do not
 * contain the {@code ':'} delimiter ({@code productName} is the RFC-canonical frozen string set;
 * {@code orgId} is a platform org identifier). If a future key dimension can contain {@code ':'},
 * its encoding belongs here.
 */
final class CacheKey {

  private static final String PREFIX = "ir:cache:";

  private CacheKey() {}

  static String of(CacheTier tier, String orgId, String productName) {
    return PREFIX + tier.token() + ':' + orgId + ':' + productName;
  }
}
