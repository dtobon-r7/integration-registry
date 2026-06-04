package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wire health state per openapi.json HealthState. Self-contained DTO enum (RFC §Spring layer
 * boundaries forbids the controller layer importing adapter.IntegrationStatus).
 */
public enum HealthState {
  HEALTHY("healthy"),
  WARNING("warning"),
  ERROR("error"),
  MISSING_DATA("missing_data"),
  DISABLED("disabled");

  private final String wire;

  HealthState(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
