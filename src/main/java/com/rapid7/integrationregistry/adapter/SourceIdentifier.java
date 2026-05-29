package com.rapid7.integrationregistry.adapter;

import java.util.Objects;

public record SourceIdentifier(String sourceType, String sourceValue) {

  static final String FIELD_SOURCE_TYPE = "sourceType";
  static final String FIELD_SOURCE_VALUE = "sourceValue";

  public SourceIdentifier {
    Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
    Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
  }
}
