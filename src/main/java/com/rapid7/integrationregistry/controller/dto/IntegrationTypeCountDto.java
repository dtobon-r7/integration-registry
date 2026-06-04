package com.rapid7.integrationregistry.controller.dto;

import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire per-type counts per openapi.json IntegrationTypeCount. {@code integrationType} is a plain
 * String (bundle/value-driven; see spec).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationTypeCountDto(String integrationType, int total, int errorCount) {

  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_TOTAL = "total";
  static final String FIELD_ERROR_COUNT = "errorCount";

  public IntegrationTypeCountDto {
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    if (total < 0) {
      throw new IllegalArgumentException(FIELD_TOTAL + " must be >= 0: " + total);
    }
    if (errorCount < 0) {
      throw new IllegalArgumentException(FIELD_ERROR_COUNT + " must be >= 0: " + errorCount);
    }
    if (errorCount > total) {
      throw new IllegalArgumentException(
          FIELD_ERROR_COUNT
              + " ("
              + errorCount
              + ") must be <= "
              + FIELD_TOTAL
              + " ("
              + total
              + ")");
    }
  }
}
