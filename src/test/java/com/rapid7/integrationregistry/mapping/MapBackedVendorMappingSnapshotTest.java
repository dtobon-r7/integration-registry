package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapBackedVendorMappingSnapshotTest {

  private static final String MAPPING_VERSION = "v1.0.0";

  private static DataSourceResolution sampleResolution() {
    return new DataSourceResolution(
        new VendorResolution(
            "microsoft-defender",
            "Microsoft Defender",
            VendorCategory.EDR,
            "microsoft",
            "Microsoft"),
        "Microsoft Defender for Endpoint");
  }

  private static Map<Object, DataSourceResolution> oneEntryIndex(DataSourceResolution resolution) {
    // The TripletKey type is private to MapBackedVendorMappingSnapshot;
    // construct keys via the package-private key(...) factory.
    Map<Object, DataSourceResolution> raw = new HashMap<>();
    raw.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint"),
        resolution);
    return raw;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static MapBackedVendorMappingSnapshot construct(
      Map<Object, DataSourceResolution> rawIndex, String version) {
    // The constructor's Map<TripletKey, DataSourceResolution> parameter is reachable
    // from this same package; use a raw type to bridge our test-side Object key map
    // (which actually holds TripletKey instances minted by key(...)).
    return new MapBackedVendorMappingSnapshot((Map) rawIndex, version);
  }

  @Test
  void lookup_shouldReturnIndexedResolution_whenTripletPresent() {
    // Arrange
    DataSourceResolution resolution = sampleResolution();
    MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(resolution), MAPPING_VERSION);

    // Act
    DataSourceResolution result =
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
    DataSourceResolution result =
        snapshot.lookup(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "not-in-the-index");

    // Assert
    assertThat(result).isSameAs(DataSourceResolution.unknown());
  }

  @Test
  void lookup_shouldReturnSameInstance_acrossRepeatedCalls() {
    // Arrange
    MapBackedVendorMappingSnapshot snapshot =
        construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

    // Act
    DataSourceResolution first =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
    DataSourceResolution second =
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
    Map<Object, DataSourceResolution> sourceMap = oneEntryIndex(sampleResolution());
    MapBackedVendorMappingSnapshot snapshot = construct(sourceMap, MAPPING_VERSION);
    DataSourceResolution otherResolution =
        new DataSourceResolution(
            new VendorResolution("jira", "Jira", VendorCategory.ITSM, "atlassian", "Atlassian"),
            "Jira");

    // Act — mutate the source map AFTER constructing the snapshot
    sourceMap.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira"),
        otherResolution);

    // Assert — the snapshot must not see the post-construction insertion
    DataSourceResolution result =
        snapshot.lookup(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");
    assertThat(result).isSameAs(DataSourceResolution.unknown());
  }
}
