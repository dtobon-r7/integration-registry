package com.rapid7.integrationregistry.controller.dto;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Wire envelope metadata per openapi.json ResponseMetadata. All three fields required. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResponseMetadataDto(boolean cacheHit, Instant asOf, String mappingVersion) {

  static final String FIELD_AS_OF = "asOf";
  static final String FIELD_MAPPING_VERSION = "mappingVersion";

  public ResponseMetadataDto {
    Objects.requireNonNull(asOf, FIELD_AS_OF);
    Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
  }
}
