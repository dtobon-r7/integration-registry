package com.rapid7.integrationregistry.controller.dto;

import java.util.Collection;
import java.util.List;

/**
 * Shared compact-constructor validation helpers for the wire DTO records. The records cannot share
 * a base type (Java records are final and cannot extend), so the recurring numeric and structural
 * invariants are collapsed here rather than hand-rolled per record. Each method throws {@link
 * IllegalArgumentException} with a message that includes the offending field name, so the existing
 * {@code withMessageContaining(FIELD_*)} test assertions continue to hold.
 *
 * <p>Per-field {@code Objects.requireNonNull(x, FIELD_X)} null checks stay inline in each record —
 * they are idiomatic and a helper would not shorten them.
 */
final class DtoValidations {

  private DtoValidations() {}

  /** Rejects a negative count. */
  static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(String.format("%s must be >= 0: %d", name, value));
    }
  }

  /** Rejects {@code value} greater than {@code ceiling}. */
  static void requireAtMost(int value, int ceiling, String valueName, String ceilingName) {
    if (value > ceiling) {
      throw new IllegalArgumentException(
          String.format("%s (%d) must be <= %s (%d)", valueName, value, ceilingName, ceiling));
    }
  }

  /** Rejects a count that does not equal {@code collection.size()}. */
  static void requireCountMatchesSize(
      int count, Collection<?> collection, String countName, String collectionName) {
    int size = collection.size();
    if (count != size) {
      throw new IllegalArgumentException(
          String.format(
              "%s (%d) must equal %s.size() (%d)", countName, count, collectionName, size));
    }
  }

  /**
   * Rejects an {@code integrations_connected} value that does not equal the sum of {@code
   * integration_type_counts[].total} — the contract (openapi.json VendorServiceCard /
   * VendorServiceCardNested) defines the former as exactly that sum. The sum is accumulated as a
   * {@code long} so an {@code int} overflow cannot silently produce a false match.
   */
  static void requireConnectedEqualsTypeCountTotals(
      int integrationsConnected, List<IntegrationTypeCountDto> typeCounts, String connectedName) {
    long sum = 0;
    for (IntegrationTypeCountDto typeCount : typeCounts) {
      sum += typeCount.total();
    }
    if (integrationsConnected != sum) {
      throw new IllegalArgumentException(
          String.format(
              "%s (%d) must equal sum of integration_type_counts[].total (%d)",
              connectedName, integrationsConnected, sum));
    }
  }
}
