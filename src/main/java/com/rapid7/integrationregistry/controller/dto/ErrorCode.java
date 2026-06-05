package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire error code per openapi.json ErrorCode. */
public enum ErrorCode {
  UNAUTHENTICATED("UNAUTHENTICATED"),
  NOT_FOUND("NOT_FOUND"),
  INTERNAL("INTERNAL");

  private final String wire;

  ErrorCode(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
