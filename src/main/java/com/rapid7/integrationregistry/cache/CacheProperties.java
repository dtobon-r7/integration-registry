package com.rapid7.integrationregistry.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTL configuration for the two cache tiers, bound from {@code integration-registry.cache.*}.
 *
 * <p>Defaults are the RFC-001 §Cache layer starting points (fresh 5 min, stale 24 h) and are
 * independently overridable per environment. Connection settings come from the standard {@code
 * spring.data.redis.*} tree (Boot auto-configuration), not this record.
 */
@ConfigurationProperties("integration-registry.cache")
public record CacheProperties(Duration freshTtl, Duration staleTtl) {

  private static final Duration DEFAULT_FRESH_TTL = Duration.ofMinutes(5);
  private static final Duration DEFAULT_STALE_TTL = Duration.ofHours(24);
  private static final String FIELD_FRESH_TTL = "freshTtl";
  private static final String FIELD_STALE_TTL = "staleTtl";

  public CacheProperties {
    if (freshTtl == null) {
      freshTtl = DEFAULT_FRESH_TTL;
    }
    if (staleTtl == null) {
      staleTtl = DEFAULT_STALE_TTL;
    }

    // Validate both TTLs are strictly positive after defaulting
    if (freshTtl.isZero() || freshTtl.isNegative()) {
      throw new IllegalArgumentException(FIELD_FRESH_TTL + " must be positive");
    }
    if (staleTtl.isZero() || staleTtl.isNegative()) {
      throw new IllegalArgumentException(FIELD_STALE_TTL + " must be positive");
    }
  }
}
