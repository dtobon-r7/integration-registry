package com.rapid7.integrationregistry.controller.dto;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Wire body for {@code GET /vendors} per openapi.json VendorsResponse. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorsResponse(
    List<VendorListEntryDto> vendors,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDORS = "vendors";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorsResponse {
    Objects.requireNonNull(vendors, FIELD_VENDORS);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendors = List.copyOf(vendors);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
