package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

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

  /**
   * Resolve a coordinator-emitted wire reason string (sourced from {@code
   * AdapterException.reasonCode()} / the coordinator's reason constants, ADR-001) to its enum
   * value. Mirrors the {@code fromWireForm} convention on the mapping-layer enums.
   *
   * @return the matching reason, or empty if {@code wire} is not a known reason value
   */
  public static Optional<UnavailableReason> fromWire(String wire) {
    for (UnavailableReason reason : values()) {
      if (reason.wire.equals(wire)) {
        return Optional.of(reason);
      }
    }
    return Optional.empty();
  }
}
