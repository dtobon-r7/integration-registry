package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire body for {@code GET /vendor-services/{id}} per openapi.json VendorServiceDetailResponse.
 * Vendor-service header (incl. parent vendor identity) plus {@code dataSources[]}. {@code
 * vendorCategory} is a String at the Java level to sidestep a known value-set mismatch (resolution
 * owned by T04/T08), but the wire contract constrains it to the openapi.json VendorCategory enum
 * values — assembly (Plan 02) must supply contract-valid values. {@code lastUpdated} is required
 * (non-null) per the openapi.json contract; the aggregator projection records may carry a null
 * internally, so assembly (Plan 02) must supply a non-null value (e.g. falling back to the
 * response's {@code as_of}) before constructing this DTO.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields dictated by the openapi.json VendorServiceDetailResponse wire contract.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceDetailResponse(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    String vendorCategory,
    HealthState aggregateHealth,
    Instant lastUpdated,
    List<DataSourceDto> dataSources,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
  static final String FIELD_DATA_SOURCES = "dataSources";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";
  static final String FIELD_LAST_UPDATED = "lastUpdated";

  public VendorServiceDetailResponse {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(lastUpdated, FIELD_LAST_UPDATED);
    Objects.requireNonNull(dataSources, FIELD_DATA_SOURCES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    dataSources = List.copyOf(dataSources);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
