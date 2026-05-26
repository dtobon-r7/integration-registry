package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeTest {

    @Test
    void values_shouldContainExactlyFourSourceTypes_whenInspected() {
        // Act
        SourceType[] all = SourceType.values();

        // Assert
        assertThat(all).containsExactly(
            SourceType.PLUGIN_NAME,
            SourceType.PRODUCT_TYPE,
            SourceType.PRODUCT_NAME,
            SourceType.INTEGRATION_ID
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert
        assertThat(SourceType.PLUGIN_NAME.wireForm()).isEqualTo("plugin_name");
        assertThat(SourceType.PRODUCT_TYPE.wireForm()).isEqualTo("product_type");
        assertThat(SourceType.PRODUCT_NAME.wireForm()).isEqualTo("product_name");
        assertThat(SourceType.INTEGRATION_ID.wireForm()).isEqualTo("integration_id");
    }

    @Test
    void fromWireForm_shouldResolveAllFourConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(SourceType.fromWireForm("plugin_name")).contains(SourceType.PLUGIN_NAME);
        assertThat(SourceType.fromWireForm("product_type")).contains(SourceType.PRODUCT_TYPE);
        assertThat(SourceType.fromWireForm("product_name")).contains(SourceType.PRODUCT_NAME);
        assertThat(SourceType.fromWireForm("integration_id")).contains(SourceType.INTEGRATION_ID);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<SourceType> result = SourceType.fromWireForm("not-a-real-source-type");

        // Assert
        assertThat(result).isEmpty();
    }
}
