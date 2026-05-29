package com.rapid7.integrationregistry.mapping.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.testsupport.StubVendorMappingSnapshot;
import org.junit.jupiter.api.Test;

class VendorMappingSnapshotHolderTest {

  @Test
  void lookup_shouldThrowIllegalState_whenNotYetSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

    // Act / Assert
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> holder.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "x"))
        .withMessageContaining("snapshot not yet loaded");
  }

  @Test
  void mappingVersion_shouldThrowIllegalState_whenNotYetSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

    // Act / Assert
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(holder::mappingVersion)
        .withMessageContaining("snapshot not yet loaded");
  }

  @Test
  void lookup_shouldDelegate_whenSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
    VendorResolution expected =
        new VendorResolution(
            "microsoft-defender",
            "Microsoft Defender",
            VendorCategory.EDR,
            "microsoft",
            "Microsoft");
    holder.set(StubVendorMappingSnapshot.returning("v1.0.0", expected));

    // Act
    VendorResolution actual =
        holder.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "any-value");

    // Assert
    assertThat(actual).isSameAs(expected);
  }

  @Test
  void mappingVersion_shouldDelegate_whenSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
    holder.set(StubVendorMappingSnapshot.returning("v9.9.9", VendorResolution.unknown()));

    // Act
    String version = holder.mappingVersion();

    // Assert
    assertThat(version).isEqualTo("v9.9.9");
  }

  @Test
  void set_shouldThrowIllegalState_whenAlreadySet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
    holder.set(StubVendorMappingSnapshot.returning("v1.0.0", VendorResolution.unknown()));

    // Act / Assert
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                holder.set(
                    StubVendorMappingSnapshot.returning("v2.0.0", VendorResolution.unknown())))
        .withMessageContaining("already set");
  }

  @Test
  void set_shouldThrowNpe_whenNullSnapshot() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

    // Act / Assert
    assertThatNullPointerException().isThrownBy(() -> holder.set(null)).withMessage("snapshot");
  }

  @Test
  void isLoaded_shouldReturnFalse_whenNotYetSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

    // Act / Assert
    assertThat(holder.isLoaded()).isFalse();
  }

  @Test
  void isLoaded_shouldReturnTrue_whenSet() {
    // Arrange
    VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
    holder.set(StubVendorMappingSnapshot.returning("v1.0.0", VendorResolution.unknown()));

    // Act / Assert
    assertThat(holder.isLoaded()).isTrue();
  }
}
