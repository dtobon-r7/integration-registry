package com.rapid7.integrationregistry.adapter.insightidr;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the InsightIDR adapter, bound from {@code
 * integration-registry.insightidr.*}.
 *
 * <p>{@code baseUrl} (scheme+host of the CMS event-sources API) and {@code idrBase} (base for
 * {@code configuration_url} deep-links) are required — the deploy environment supplies them; absent
 * config fails fast at binding. {@code timeout} (search/per-adapter), {@code detailTimeout}
 * (per-detail-call), {@code stalenessThreshold}, and {@code detailConcurrency} default to the
 * RFC-001 §Fan-out starting points and are tunable per environment.
 */
@ConfigurationProperties("integration-registry.insightidr")
public record InsightIDRProperties(
    String baseUrl,
    String idrBase,
    Duration timeout,
    Duration detailTimeout,
    Duration stalenessThreshold,
    Integer detailConcurrency) {

  private static final String FIELD_BASE_URL = "baseUrl";
  private static final String FIELD_IDR_BASE = "idrBase";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration DEFAULT_DETAIL_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration DEFAULT_STALENESS = Duration.ofHours(24);
  private static final int DEFAULT_DETAIL_CONCURRENCY = 60;

  public InsightIDRProperties {
    baseUrl = stripTrailingSlash(requireText(baseUrl, FIELD_BASE_URL));
    idrBase = stripTrailingSlash(requireText(idrBase, FIELD_IDR_BASE));
    if (timeout == null) {
      timeout = DEFAULT_TIMEOUT;
    }
    if (detailTimeout == null) {
      detailTimeout = DEFAULT_DETAIL_TIMEOUT;
    }
    if (stalenessThreshold == null) {
      stalenessThreshold = DEFAULT_STALENESS;
    }
    if (detailConcurrency == null) {
      detailConcurrency = DEFAULT_DETAIL_CONCURRENCY;
    }
    if (detailConcurrency < 1) {
      throw new IllegalArgumentException(
          "detailConcurrency must be >= 1, was " + detailConcurrency);
    }
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
