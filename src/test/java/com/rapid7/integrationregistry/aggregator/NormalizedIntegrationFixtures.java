package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import java.time.Instant;

/**
 * Static helpers for building {@link NormalizedIntegration} test fixtures — keeps each test
 * scenario readable by collapsing the 9-arg constructor down to a 3- or 4-arg call. Real records,
 * no mocks.
 *
 * <p>Conventions:
 *
 * <ul>
 *   <li>{@code productName} / {@code integrationType} / {@code sourceType} use the canonical
 *       wire-form strings the adapters write (see RFC-001 §Canonical productName values,
 *       §source_type enum, §Integration Types).
 *   <li>{@code customerAccountId} is fixed to {@code "test-org"} — the aggregator does not consume
 *       it; tests do not care.
 *   <li>{@code configurationUrl} is fixed per product. The aggregator does not consume it for
 *       grouping; tests do not care.
 *   <li>{@code lastSuccess} of {@code null} is supported (the record permits it).
 * </ul>
 */
final class NormalizedIntegrationFixtures {

  static final String CUSTOMER_ACCOUNT_ID = "test-org";

  private NormalizedIntegrationFixtures() {}

  static NormalizedIntegration idrInstance(
      String integrationId, String sourceValue, IntegrationStatus status) {
    return idrInstance(integrationId, sourceValue, status, null);
  }

  static NormalizedIntegration idrInstance(
      String integrationId, String sourceValue, IntegrationStatus status, Instant lastSuccess) {
    return new NormalizedIntegration(
        integrationId,
        new SourceIdentifier("product_type", sourceValue),
        "InsightIDR",
        "SIEM Event Source",
        "idr-" + integrationId,
        status,
        lastSuccess,
        "https://idr.example/eventsources/" + integrationId,
        CUSTOMER_ACCOUNT_ID);
  }

  static NormalizedIntegration iconInstance(
      String integrationId, String sourceValue, IntegrationStatus status) {
    return iconInstance(integrationId, sourceValue, status, null);
  }

  static NormalizedIntegration iconInstance(
      String integrationId, String sourceValue, IntegrationStatus status, Instant lastSuccess) {
    return new NormalizedIntegration(
        integrationId,
        new SourceIdentifier("plugin_name", sourceValue),
        "InsightConnect",
        "Automation Plugin",
        null, // ICON exposes no per-instance label
        status,
        lastSuccess,
        "https://icon.example/connections/" + integrationId,
        CUSTOMER_ACCOUNT_ID);
  }

  /** Escape hatch for unmapped / unmappable / cross-type scenarios. */
  static NormalizedIntegration instance(
      String productName,
      String sourceType,
      String sourceValue,
      String integrationType,
      IntegrationStatus status,
      String integrationId,
      Instant lastSuccess) {
    return new NormalizedIntegration(
        integrationId,
        new SourceIdentifier(sourceType, sourceValue),
        productName,
        integrationType,
        null,
        status,
        lastSuccess,
        "https://example/" + integrationId,
        CUSTOMER_ACCOUNT_ID);
  }
}
