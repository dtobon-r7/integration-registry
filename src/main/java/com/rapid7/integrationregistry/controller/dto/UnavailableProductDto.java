package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire partial-failure record per openapi.json UnavailableProduct. {@code staleSince} is the only
 * DTO field that omits when null ({@code stale_since} present only when {@code stale=true}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UnavailableProductDto(
    String productName,
    boolean stale,
    UnavailableReason reason,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant staleSince) {

  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_REASON = "reason";

  public UnavailableProductDto {
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(reason, FIELD_REASON);
  }
}
