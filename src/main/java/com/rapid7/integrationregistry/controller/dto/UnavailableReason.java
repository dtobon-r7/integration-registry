package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire unavailable-reason per openapi.json UnavailableReason. */
public enum UnavailableReason {
  TIMEOUT("timeout"),
  UPSTREAM_5XX("upstream_5xx"),
  AUTH_FAILURE("auth_failure"),
  NO_DATA("no_data");

  private final String wire;

  UnavailableReason(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
