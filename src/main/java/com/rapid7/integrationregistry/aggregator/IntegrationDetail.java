package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-instance detail under a {@link DataSourceDetail} per RFC-001 §Integration entity. {@code
 * integrationLabel} and {@code lastSuccessTimestamp} are nullable per the RFC: {@code
 * integrationLabel} when the source product exposes no per-instance customer-given name, and {@code
 * lastSuccessTimestamp} when no successful activity has ever been recorded.
 */
public record IntegrationDetail(
    String integrationId,
    String dataSourceId,
    String integrationLabel,
    IntegrationStatus status,
    Instant lastSuccessTimestamp,
    String configurationUrl) {

  static final String FIELD_INTEGRATION_ID = "integrationId";
  static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
  static final String FIELD_STATUS = "status";
  static final String FIELD_CONFIGURATION_URL = "configurationUrl";

  public IntegrationDetail {
    Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
    Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
  }
}
