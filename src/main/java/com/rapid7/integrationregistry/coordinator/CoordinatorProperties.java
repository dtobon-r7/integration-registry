package com.rapid7.integrationregistry.coordinator;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fan-out timing configuration, bound from {@code integration-registry.coordinator.*}.
 *
 * <p>Defaults are RFC-001 §Operational-defaults starting points (Q5: numbers deferred to staging,
 * shape pinned, every value per-environment configurable). {@code perAdapterTimeout} is keyed by
 * the adapter's canonical {@code productName()} ({@code InsightConnect}, {@code InsightIDR}, …); a
 * product with no entry falls back to {@code defaultPerAdapterTimeout}.
 */
@ConfigurationProperties("integration-registry.coordinator")
public record CoordinatorProperties(
    Duration totalDeadline,
    Duration defaultPerAdapterTimeout,
    Map<String, Duration> perAdapterTimeout) {

  private static final Duration DEFAULT_TOTAL_DEADLINE = Duration.ofSeconds(20);
  private static final Duration DEFAULT_PER_ADAPTER_TIMEOUT = Duration.ofSeconds(10);

  public CoordinatorProperties {
    if (totalDeadline == null) {
      totalDeadline = DEFAULT_TOTAL_DEADLINE;
    }
    if (defaultPerAdapterTimeout == null) {
      defaultPerAdapterTimeout = DEFAULT_PER_ADAPTER_TIMEOUT;
    }
    perAdapterTimeout = perAdapterTimeout == null ? Map.of() : Map.copyOf(perAdapterTimeout);

    requirePositive(totalDeadline, "totalDeadline");
    requirePositive(defaultPerAdapterTimeout, "defaultPerAdapterTimeout");
    perAdapterTimeout.forEach(
        (product, timeout) -> requirePositive(timeout, "perAdapterTimeout." + product));
  }

  /** The configured per-adapter timeout for {@code productName}, or the default when unset. */
  public Duration perAdapterTimeoutFor(String productName) {
    return perAdapterTimeout.getOrDefault(productName, defaultPerAdapterTimeout);
  }

  private static void requirePositive(Duration value, String field) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
