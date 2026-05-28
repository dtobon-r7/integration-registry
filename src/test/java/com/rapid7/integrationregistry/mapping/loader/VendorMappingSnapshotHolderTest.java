package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorMappingSnapshotHolderTest {

    private static VendorMappingSnapshot stubSnapshot(String version, VendorResolution resolution) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return resolution;
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void lookup_shouldThrowIllegalState_whenNotYetSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

        // Act / Assert
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> holder.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "x"))
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
        VendorResolution expected = new VendorResolution(
            "microsoft-defender", "Microsoft Defender",
            VendorCategory.EDR, "microsoft", "Microsoft");
        holder.set(stubSnapshot("v1.0.0", expected));

        // Act
        VendorResolution actual = holder.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "any-value");

        // Assert
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void mappingVersion_shouldDelegate_whenSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
        holder.set(stubSnapshot("v9.9.9", VendorResolution.unknown()));

        // Act
        String version = holder.mappingVersion();

        // Assert
        assertThat(version).isEqualTo("v9.9.9");
    }

    @Test
    void set_shouldThrowIllegalState_whenAlreadySet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
        holder.set(stubSnapshot("v1.0.0", VendorResolution.unknown()));

        // Act / Assert
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> holder.set(stubSnapshot("v2.0.0", VendorResolution.unknown())))
            .withMessageContaining("already set");
    }

    @Test
    void set_shouldThrowNpe_whenNullSnapshot() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> holder.set(null))
            .withMessage("snapshot");
    }
}
