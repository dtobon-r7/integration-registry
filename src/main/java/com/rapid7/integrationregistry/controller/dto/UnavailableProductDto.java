package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire partial-failure record per openapi.json UnavailableProduct. {@code staleSince} is the only
 * DTO field that omits when null ({@code stale_since} present only when {@code stale=true}).
 *
 * <p>Enforces the contract's stale/stale_since coupling that the JSON schema cannot express (it
 * does not make {@code stale_since} conditionally required): {@code staleSince} must be non-null
 * exactly when {@code stale=true}, and must be null when {@code stale=false}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UnavailableProductDto(
    String productName,
    boolean stale,
    UnavailableReason reason,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant staleSince) {

  static final String FIELD_STALE = "stale";
  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_REASON = "reason";
  static final String FIELD_STALE_SINCE = "staleSince";

  public UnavailableProductDto {
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(reason, FIELD_REASON);
    if (stale && staleSince == null) {
      throw new IllegalArgumentException(
          FIELD_STALE_SINCE + " must be non-null when " + FIELD_STALE + "=true");
    }
    if (!stale && staleSince != null) {
      throw new IllegalArgumentException(
          FIELD_STALE_SINCE + " must be null when " + FIELD_STALE + "=false: " + staleSince);
    }
  }
}
