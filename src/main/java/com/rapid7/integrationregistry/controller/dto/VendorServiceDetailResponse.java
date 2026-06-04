package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire body for {@code GET /vendor-services/{id}} per openapi.json VendorServiceDetailResponse.
 * Vendor-service header (incl. parent vendor identity) plus {@code dataSources[]}. {@code
 * vendorCategory} is plain String; {@code lastUpdated} nullable (explicit null).
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

  public VendorServiceDetailResponse {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(dataSources, FIELD_DATA_SOURCES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    dataSources = List.copyOf(dataSources);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
