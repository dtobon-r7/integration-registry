package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire flat vendor-service card per openapi.json VendorServiceCard ({@code GET /vendor-services}).
 * Embeds {@code vendorId}/{@code vendorName} so the UI renders the vendor filter chip without a
 * lookup. {@code vendorCategory} is a plain String (bundle/value-driven). {@code lastUpdated} is
 * nullable (explicit null).
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields dictated by the openapi.json VendorServiceCard wire contract, not by ergonomics.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceCardDto(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    String vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCountDto> integrationTypeCounts,
    List<String> productsConnected,
    HealthState aggregateHealth,
    Instant lastUpdated) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
  static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
  static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

  public VendorServiceCardDto {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
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
