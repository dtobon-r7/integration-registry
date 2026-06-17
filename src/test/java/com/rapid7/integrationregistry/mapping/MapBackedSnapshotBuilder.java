package com.rapid7.integrationregistry.mapping;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only builder that produces real {@link MapBackedVendorMappingSnapshot} instances via the
 * package-scoped {@link MapBackedVendorMappingSnapshot#key(ProductName, SourceType, String)} seam.
 *
 * <p>Lives in the {@code mapping} test source package so it can reach the package-private factory
 * and constructor without exposing them. {@code TripletKey} stays fully encapsulated — this builder
 * uses the same {@code Map<Object, VendorResolution>} bridge pattern that {@link
 * MapBackedVendorMappingSnapshotTest} relies on.
 *
 * <p>Public so tests in other packages (e.g. {@code aggregator}) can drive a real production
 * snapshot through the canonical lookup code path:
 *
 * <pre>{@code
 * VendorMappingSnapshot snapshot = MapBackedSnapshotBuilder
 *     .with("v1.42.0")
 *     .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
 *          "microsoft-defender-endpoint",
 *          new VendorResolution("microsoft-defender", "Microsoft Defender",
 *              VendorCategory.EDR, "microsoft", "Microsoft"))
 *     .build();
 * }</pre>
 */
public final class MapBackedSnapshotBuilder {

  private final Map<Object, DataSourceResolution> index = new HashMap<>();
  private final String mappingVersion;

  private MapBackedSnapshotBuilder(String mappingVersion) {
    this.mappingVersion = Objects.requireNonNull(mappingVersion, "mappingVersion");
  }

  public static MapBackedSnapshotBuilder with(String mappingVersion) {
    return new MapBackedSnapshotBuilder(mappingVersion);
  }

  /**
   * Map a triplet, defaulting the data-source {@code displayName} to the identity's vendor-service
   * name — convenience for tests that do not assert on display names.
   */
  public MapBackedSnapshotBuilder map(
      ProductName productName,
      SourceType sourceType,
      String sourceValue,
      VendorResolution identity) {
    Objects.requireNonNull(identity, "identity");
    return map(productName, sourceType, sourceValue, identity, identity.vendorServiceName());
  }

  /** Map a triplet with an explicit curated {@code displayName}. */
  public MapBackedSnapshotBuilder map(
      ProductName productName,
      SourceType sourceType,
      String sourceValue,
      VendorResolution identity,
      String displayName) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(displayName, "displayName");
    index.put(
        MapBackedVendorMappingSnapshot.key(productName, sourceType, sourceValue),
        new DataSourceResolution(identity, displayName));
    return this;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public VendorMappingSnapshot build() {
    // The constructor's Map<TripletKey, VendorResolution> parameter is reachable from this same
    // package; use a raw-type bridge to pass our Object-keyed map (which actually holds TripletKey
    // instances minted by key(...)). Mirrors the pattern in MapBackedVendorMappingSnapshotTest.
    return new MapBackedVendorMappingSnapshot((Map) index, mappingVersion);
  }
}
