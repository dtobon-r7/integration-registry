package com.rapid7.integrationregistry.adapter;

import java.time.Instant;
import java.util.Objects;

/**
 * Adapter-shaped record describing a single third-party integration as observed by a Rapid7
 * product. Produced by {@link IntegrationAdapter#fetch} implementations and consumed by the future
 * aggregator and response-assembly layers.
 *
 * <p>{@code productName} must equal the value returned by the producing adapter's {@link
 * IntegrationAdapter#productName()}; downstream resolution against the vendor mapping snapshot keys
 * on this pair.
 *
 * <p>{@code customerAccountId} MUST equal the {@code orgId} argument passed to {@link
 * IntegrationAdapter#fetch}; the coordinator and cache key on this value, so any divergence will
 * split-brain entries. Populated internally; never surfaced on API responses (RFC-001 §Normalized
 * integration record).
 */
public record NormalizedIntegration(
    String integrationId,
    SourceIdentifier sourceIdentifier,
    String productName,
    String integrationType,
    String integrationLabel,
    IntegrationStatus status,
    Instant lastSuccessTimestamp,
    String configurationUrl,
    String customerAccountId) {

  static final String FIELD_INTEGRATION_ID = "integrationId";
  static final String FIELD_SOURCE_IDENTIFIER = "sourceIdentifier";
  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_STATUS = "status";
  static final String FIELD_CONFIGURATION_URL = "configurationUrl";
  static final String FIELD_CUSTOMER_ACCOUNT_ID = "customerAccountId";

  public NormalizedIntegration {
    Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
    Objects.requireNonNull(sourceIdentifier, FIELD_SOURCE_IDENTIFIER);
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
    Objects.requireNonNull(customerAccountId, FIELD_CUSTOMER_ACCOUNT_ID);
  }
}
