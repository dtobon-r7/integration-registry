package com.rapid7.integrationregistry.controller.dto;

import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire per-type counts per openapi.json IntegrationTypeCount. {@code integrationType} is a String
 * at the Java level to sidestep a known value-set mismatch (resolution owned by T04/T08), but the
 * wire contract constrains it to the openapi.json IntegrationType enum values — assembly (Plan 02)
 * must supply contract-valid values.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationTypeCountDto(String integrationType, int total, int errorCount) {

  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_TOTAL = "total";
  static final String FIELD_ERROR_COUNT = "errorCount";

  public IntegrationTypeCountDto {
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    DtoValidations.requireNonNegative(total, FIELD_TOTAL);
    DtoValidations.requireNonNegative(errorCount, FIELD_ERROR_COUNT);
    DtoValidations.requireAtMost(errorCount, total, FIELD_ERROR_COUNT, FIELD_TOTAL);
  }
}
