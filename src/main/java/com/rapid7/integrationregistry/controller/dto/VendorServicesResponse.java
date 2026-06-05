package com.rapid7.integrationregistry.controller.dto;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Wire body for {@code GET /vendor-services} per openapi.json VendorServicesResponse. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServicesResponse(
    List<VendorServiceCardDto> vendorServices,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_SERVICES = "vendorServices";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorServicesResponse {
    Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendorServices = List.copyOf(vendorServices);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
