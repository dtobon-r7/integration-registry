package com.rapid7.integrationregistry.controller.dto;

import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Wire error envelope per openapi.json ErrorEnvelope: {@code {"error":{"code","message"}}}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorEnvelopeDto(ErrorBody error) {

  static final String FIELD_ERROR = "error";

  public ErrorEnvelopeDto {
    Objects.requireNonNull(error, FIELD_ERROR);
  }

  /** Nested {@code error} object. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ErrorBody(ErrorCode code, String message) {

    static final String FIELD_CODE = "code";
    static final String FIELD_MESSAGE = "message";

    public ErrorBody {
      Objects.requireNonNull(code, FIELD_CODE);
      Objects.requireNonNull(message, FIELD_MESSAGE);
    }
  }
}
