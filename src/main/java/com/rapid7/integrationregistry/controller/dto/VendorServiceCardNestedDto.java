package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire vendor-scoped vendor-service card per openapi.json VendorServiceCardNested ({@code GET
 * /vendors/{vendor_id}}). Omits {@code vendorId}/{@code vendorName} present in the flat card — the
 * parent vendor scope already pins them. {@code lastUpdated} is nullable (explicit null).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceCardNestedDto(
    String vendorServiceId,
    String vendorServiceName,
    String vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCountDto> integrationTypeCounts,
    List<String> productsConnected,
    HealthState aggregateHealth,
    Instant lastUpdated) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
  static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
  static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

  public VendorServiceCardNestedDto {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
    Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    if (integrationsConnected < 0) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
    }
    integrationTypeCounts = List.copyOf(integrationTypeCounts);
    productsConnected = List.copyOf(productsConnected);
  }
}
