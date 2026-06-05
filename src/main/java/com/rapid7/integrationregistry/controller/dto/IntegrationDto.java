package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire per-instance row per openapi.json Integration. Deliberately carries NO {@code
 * data_source_id} — that internal FK lives only on the aggregator projection record, not the wire.
 * {@code integrationLabel} and {@code lastSuccessTimestamp} are nullable and render as explicit
 * JSON null.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationDto(
    String integrationId,
    String integrationLabel,
    HealthState status,
    Instant lastSuccessTimestamp,
    String configurationUrl) {

  static final String FIELD_INTEGRATION_ID = "integrationId";
  static final String FIELD_STATUS = "status";
  static final String FIELD_CONFIGURATION_URL = "configurationUrl";

  public IntegrationDto {
    Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
  }
}
