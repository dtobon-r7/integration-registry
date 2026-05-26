package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProductNameTest {

    @Test
    void values_shouldContainExactlySixProducts_whenInspected() {
        // Act
        ProductName[] all = ProductName.values();

        // Assert
        assertThat(all).containsExactly(
            ProductName.INSIGHT_IDR,
            ProductName.INSIGHT_CONNECT,
            ProductName.SURFACE_COMMAND,
            ProductName.INSIGHT_VM,
            ProductName.INSIGHT_CLOUD_SEC,
            ProductName.INSIGHT_APP_SEC
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert — note the deliberate two-word form for Surface Command (with a space)
        assertThat(ProductName.INSIGHT_IDR.wireForm()).isEqualTo("InsightIDR");
        assertThat(ProductName.INSIGHT_CONNECT.wireForm()).isEqualTo("InsightConnect");
        assertThat(ProductName.SURFACE_COMMAND.wireForm()).isEqualTo("Surface Command");
        assertThat(ProductName.INSIGHT_VM.wireForm()).isEqualTo("InsightVM");
        assertThat(ProductName.INSIGHT_CLOUD_SEC.wireForm()).isEqualTo("InsightCloudSec");
        assertThat(ProductName.INSIGHT_APP_SEC.wireForm()).isEqualTo("InsightAppSec");
    }

    @Test
    void fromWireForm_shouldResolveAllSixConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(ProductName.fromWireForm("InsightIDR")).contains(ProductName.INSIGHT_IDR);
        assertThat(ProductName.fromWireForm("InsightConnect")).contains(ProductName.INSIGHT_CONNECT);
        assertThat(ProductName.fromWireForm("Surface Command")).contains(ProductName.SURFACE_COMMAND);
        assertThat(ProductName.fromWireForm("InsightVM")).contains(ProductName.INSIGHT_VM);
        assertThat(ProductName.fromWireForm("InsightCloudSec")).contains(ProductName.INSIGHT_CLOUD_SEC);
        assertThat(ProductName.fromWireForm("InsightAppSec")).contains(ProductName.INSIGHT_APP_SEC);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<ProductName> result = ProductName.fromWireForm("MadeUpProduct");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenSurfaceCommandSpaceIsMissing() {
        // Arrange
        // Guards against the easy-to-make "SurfaceCommand" (no space) typo —
        // RFC-001 is explicit that the wire form is "Surface Command".

        // Act
        Optional<ProductName> result = ProductName.fromWireForm("SurfaceCommand");

        // Assert
        assertThat(result).isEmpty();
    }
}
