package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire flat vendor-service card per openapi.json VendorServiceCard ({@code GET /vendor-services}).
 * Embeds {@code vendorId}/{@code vendorName} so the UI renders the vendor filter chip without a
 * lookup. {@code vendorCategory} is a String at the Java level to sidestep a known value-set
 * mismatch (resolution owned by T04/T08), but the wire contract constrains it to the openapi.json
 * VendorCategory enum values — assembly (Plan 02) must supply contract-valid values. {@code
 * lastUpdated} is required (non-null) per the openapi.json contract; the aggregator projection
 * records may carry a null internally, so assembly (Plan 02) must supply a non-null value (e.g.
 * falling back to the response's {@code as_of}) before constructing this DTO. Enforces the contract
 * invariant {@code integrationsConnected == sum(integrationTypeCounts[].total)}.
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
  static final String FIELD_LAST_UPDATED = "lastUpdated";

  public VendorServiceCardDto {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
    Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(lastUpdated, FIELD_LAST_UPDATED);
    DtoValidations.requireNonNegative(integrationsConnected, FIELD_INTEGRATIONS_CONNECTED);
    integrationTypeCounts = List.copyOf(integrationTypeCounts);
    productsConnected = List.copyOf(productsConnected);
    DtoValidations.requireConnectedEqualsTypeCountTotals(
        integrationsConnected, integrationTypeCounts, FIELD_INTEGRATIONS_CONNECTED);
  }
}
