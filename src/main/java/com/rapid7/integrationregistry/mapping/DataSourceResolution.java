package com.rapid7.integrationregistry.mapping;

import java.util.Objects;

/**
 * Result of resolving a raw product source identifier to its canonical vendor-service {@code
 * identity} plus the curated, data-source-level {@code displayName} that the UI renders in the
 * expanded data-source row.
 *
 * <p>The two fields sit at different cardinalities on purpose: many data sources can share one
 * vendor service (the cross-product merge), so each carries its own {@code displayName} while
 * sharing an identical {@link VendorResolution} {@code identity}. Keeping {@code displayName} here
 * — rather than on {@link VendorResolution} — is what lets the bundle-integrity check in {@code
 * VendorAggregator} keep comparing identities by value without two merged data sources looking
 * inconsistent.
 *
 * <p>Use {@link #unknown()} for unmapped triplets: its {@code identity} is the {@link
 * VendorResolution#unknown()} singleton and its {@code displayName} is the fixed label {@code
 * "Unknown"} (never the raw {@code sourceValue}).
 */
public record DataSourceResolution(VendorResolution identity, String displayName) {

  static final String FIELD_IDENTITY = "identity";
  static final String FIELD_DISPLAY_NAME = "displayName";

  private static final String UNKNOWN_DISPLAY_NAME = "Unknown";

  private static final DataSourceResolution UNKNOWN =
      new DataSourceResolution(VendorResolution.unknown(), UNKNOWN_DISPLAY_NAME);

  public DataSourceResolution {
    Objects.requireNonNull(identity, FIELD_IDENTITY);
    Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
  }

  public static DataSourceResolution unknown() {
    return UNKNOWN;
  }
}
