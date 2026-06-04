package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire body for {@code GET /vendors/{vendor_id}} per openapi.json VendorDetailResponse. Vendor
 * header (with rolled-up {@code aggregateHealth} + nullable {@code lastUpdated}) plus nested {@code
 * vendorServices[]} of VendorServiceCardNestedDto.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorDetailResponse(
    String vendorId,
    String vendorName,
    HealthState aggregateHealth,
    Instant lastUpdated,
    List<VendorServiceCardNestedDto> vendorServices,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
  static final String FIELD_VENDOR_SERVICES = "vendorServices";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorDetailResponse {
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendorServices = List.copyOf(vendorServices);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
