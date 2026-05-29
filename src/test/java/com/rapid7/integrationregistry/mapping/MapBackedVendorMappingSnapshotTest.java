package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapBackedVendorMappingSnapshotTest {

  private static final String MAPPING_VERSION = "v1.0.0";

  private static VendorResolution sampleResolution() {
    return new VendorResolution(
        "microsoft-defender", "Microsoft Defender", VendorCategory.EDR, "microsoft", "Microsoft");
  }

  private static Map<Object, VendorResolution> oneEntryIndex(VendorResolution resolution) {
    // The TripletKey type is private to MapBackedVendorMappingSnapshot;
    // construct keys via the package-private key(...) factory.
    Map<Object, VendorResolution> raw = new HashMap<>();
    raw.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint"),
        resolution);
    return raw;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static MapBackedVendorMappingSnapshot construct(
      Map<Object, VendorResolution> rawIndex, String version) {
    // The constructor's Map<TripletKey, VendorResolution> parameter is reachable
    // from this same package; use a raw type to bridge our test-side Object key map
    // (which actually holds TripletKey instances minted by key(...)).
    return new MapBackedVendorMappingSnapshot((Map) rawIndex, version);
  }

  @Test
  void lookup_shouldReturnIndexedResolution_whenTripletPresent() {
    // Arrange
    VendorResolution resolution = sampleResolution();
    MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(resolution), MAPPING_VERSION);

    // Act
    VendorResolution result =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

    // Assert
    assertThat(result).isSameAs(resolution);
  }

  @Test
  void lookup_shouldReturnUnknownResolution_whenTripletAbsent() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act
    VendorResolution result =
        snapshot.lookup(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "not-in-the-index");

    // Assert
    assertThat(result).isSameAs(VendorResolution.unknown());
  }

  @Test
  void lookup_shouldReturnSameInstance_acrossRepeatedCalls() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act
    VendorResolution first =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
    VendorResolution second =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

    // Assert
    assertThat(first).isSameAs(second);
  }

  @Test
  void lookup_shouldThrowNPE_whenProductNameIsNull() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> snapshot.lookup(null, SourceType.PRODUCT_TYPE, "x"))
        .withMessage("productName");
  }

  @Test
  void lookup_shouldThrowNPE_whenSourceTypeIsNull() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> snapshot.lookup(ProductName.INSIGHT_IDR, null, "x"))
        .withMessage("sourceType");
  }

  @Test
  void lookup_shouldThrowNPE_whenSourceValueIsNull() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> snapshot.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, null))
        .withMessage("sourceValue");
  }

  @Test
  void mappingVersion_shouldReturnConstructorValue_whenAccessed() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act
    String version = snapshot.mappingVersion();

    // Assert
    assertThat(version).isEqualTo(MAPPING_VERSION);
  }

  @Test
  void constructor_shouldThrowNPE_whenIndexIsNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> construct(null, MAPPING_VERSION))
        .withMessage("index");
  }

  @Test
  void constructor_shouldThrowNPE_whenMappingVersionIsNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> construct(oneEntryIndex(sampleResolution()), null))
        .withMessage("mappingVersion");
  }

  @Test
  void constructor_shouldDefensivelyCopy_whenMutatingSourceMapAfterConstruction() {
    // Arrange
    Map<Object, VendorResolution> sourceMap = oneEntryIndex(sampleResolution());
    MapBackedVendorMappingSnapshot snapshot = construct(sourceMap, MAPPING_VERSION);
    VendorResolution otherResolution =
        new VendorResolution("jira", "Jira", VendorCategory.ITSM, "atlassian", "Atlassian");

    // Act — mutate the source map AFTER constructing the snapshot
    sourceMap.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira"),
        otherResolution);

    // Assert — the snapshot must not see the post-construction insertion
    VendorResolution result =
        snapshot.lookup(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");
    assertThat(result).isSameAs(VendorResolution.unknown());
  }
}
