package com.rapid7.integrationregistry.cache;

import java.util.Objects;

/**
 * The single Valkey-key construction site for the cache. Keys are {@code
 * ir:cache:{tier}:{orgId}:{productName}}.
 *
 * <p>This is deliberately the only place a cache key is built: the two tiers stay independent
 * because their keys differ by the {@code tier} segment, and a future change to a user-scoped key —
 * {@code (orgId, userId, productName)} — touches only this method and its direct callers.
 *
 * <p>The no-{@code ':'} rule is enforced: {@code orgId} and {@code productName} must not contain
 * the {@code ':'} delimiter ({@code productName} is the RFC-canonical frozen string set; {@code
 * orgId} is a platform org identifier). If a future key dimension can contain {@code ':'}, its
 * encoding belongs here.
 */
final class CacheKey {

  private static final String PREFIX = "ir:cache:";
  private static final String FIELD_ORG_ID = "orgId";
  private static final String FIELD_PRODUCT_NAME = "productName";

  private CacheKey() {}

  static String of(CacheTier tier, String orgId, String productName) {
    Objects.requireNonNull(orgId, FIELD_ORG_ID);
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);

    if (orgId.contains(":")) {
      throw new IllegalArgumentException("orgId must not contain ':'");
    }
    if (productName.contains(":")) {
      throw new IllegalArgumentException("productName must not contain ':'");
    }

    return PREFIX + tier.token() + ':' + orgId + ':' + productName;
  }
}
