package com.rapid7.integrationregistry.controller.dto;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire data-source block per openapi.json DataSource. {@code integrationType} and {@code
 * productName} are String at the Java level to sidestep a known value-set mismatch (resolution
 * owned by T04/T08), but the wire contract constrains them to the openapi.json IntegrationType /
 * ProductName enum values — assembly (Plan 02) must supply contract-valid values. Enforces {@code
 * integrationsCount == integrations.size()}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DataSourceDto(
    String dataSourceId,
    String displayName,
    String integrationType,
    String productName,
    HealthState status,
    int integrationsCount,
    List<IntegrationDto> integrations) {

  static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
  static final String FIELD_DISPLAY_NAME = "displayName";
  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_STATUS = "status";
  static final String FIELD_INTEGRATIONS_COUNT = "integrationsCount";
  static final String FIELD_INTEGRATIONS = "integrations";

  public DataSourceDto {
    Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
    Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
    DtoValidations.requireNonNegative(integrationsCount, FIELD_INTEGRATIONS_COUNT);
    DtoValidations.requireCountMatchesSize(
        integrationsCount, integrations, FIELD_INTEGRATIONS_COUNT, FIELD_INTEGRATIONS);
    integrations = List.copyOf(integrations);
  }
}
