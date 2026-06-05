package com.rapid7.integrationregistry.controller.dto;

import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Wire lightweight vendor row per openapi.json VendorListEntry ({@code GET /vendors}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorListEntryDto(String vendorId, String vendorName, int vendorServicesCount) {

  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";

  public VendorListEntryDto {
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    DtoValidations.requireNonNegative(vendorServicesCount, FIELD_VENDOR_SERVICES_COUNT);
  }
}
