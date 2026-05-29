package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-rolled in-memory {@link VendorMappingSnapshot} for tests. Mirrors production behavior:
 * returns {@link VendorResolution#unknown()} on miss, never null, never throws on unmapped input.
 *
 * <p>Builder-style construction keeps test arrangement readable:
 *
 * <pre>{@code
 * VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot
 *     .with("v1.42.0")
 *     .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
 *          "microsoft-defender-endpoint",
 *          new VendorResolution("microsoft-defender", "Microsoft Defender",
 *              VendorCategory.EDR, "microsoft", "Microsoft"))
 *     .build();
 * }</pre>
 */
final class FakeVendorMappingSnapshot implements VendorMappingSnapshot {

  private record TripletKey(ProductName productName, SourceType sourceType, String sourceValue) {
    private TripletKey {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(sourceType, "sourceType");
      Objects.requireNonNull(sourceValue, "sourceValue");
    }
  }

  private final Map<TripletKey, VendorResolution> index;
  private final String mappingVersion;

  private FakeVendorMappingSnapshot(
      Map<TripletKey, VendorResolution> index, String mappingVersion) {
    this.index = Map.copyOf(index);
    this.mappingVersion = mappingVersion;
  }

  static Builder with(String mappingVersion) {
    return new Builder(mappingVersion);
  }

  @Override
  public VendorResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    return index.getOrDefault(
        new TripletKey(productName, sourceType, sourceValue), VendorResolution.unknown());
  }

  @Override
  public String mappingVersion() {
    return mappingVersion;
  }

  static final class Builder {
    private final Map<TripletKey, VendorResolution> index = new HashMap<>();
    private final String mappingVersion;

    private Builder(String mappingVersion) {
      this.mappingVersion = Objects.requireNonNull(mappingVersion, "mappingVersion");
    }

    Builder map(
        ProductName productName,
        SourceType sourceType,
        String sourceValue,
        VendorResolution resolution) {
      Objects.requireNonNull(resolution, "resolution");
      index.put(new TripletKey(productName, sourceType, sourceValue), resolution);
      return this;
    }

    FakeVendorMappingSnapshot build() {
      return new FakeVendorMappingSnapshot(index, mappingVersion);
    }
  }
}
