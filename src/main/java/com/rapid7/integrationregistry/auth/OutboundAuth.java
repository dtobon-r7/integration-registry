package com.rapid7.integrationregistry.auth;

import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral carrier for the outbound auth headers the read path forwards to product
 * adapters. Exists so the {@code service} layer can pass auth to the {@code coordinator} without
 * importing {@code org.springframework.http.HttpHeaders} (forbidden by the RFC-001 §Spring layer
 * boundaries ArchUnit rule). The controller (Plan 03) builds it from the inbound request; the
 * coordinator converts it back to {@code HttpHeaders} at the adapter boundary.
 *
 * <p>This package is a deliberate leaf: it depends on nothing internal, so every layer may import
 * it without creating a boundary violation.
 */
public record OutboundAuth(Map<String, String> headers) {

  public OutboundAuth {
    Objects.requireNonNull(headers, "headers");
    headers = Map.copyOf(headers);
  }

  /** An auth carrier with no headers. */
  public static OutboundAuth empty() {
    return new OutboundAuth(Map.of());
  }

  /** An auth carrier wrapping a defensive copy of {@code headers}. */
  public static OutboundAuth of(Map<String, String> headers) {
    return new OutboundAuth(headers);
  }
}
