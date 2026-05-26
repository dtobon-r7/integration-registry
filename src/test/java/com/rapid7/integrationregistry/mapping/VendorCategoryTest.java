package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VendorCategoryTest {

    @Test
    void values_shouldContainExactlySevenCategories_whenInspected() {
        // Act
        VendorCategory[] all = VendorCategory.values();

        // Assert
        assertThat(all).containsExactly(
            VendorCategory.CLOUD_PROVIDER,
            VendorCategory.IDENTITY,
            VendorCategory.ITSM,
            VendorCategory.SIEM,
            VendorCategory.EDR,
            VendorCategory.NOTIFICATION,
            VendorCategory.OTHER
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert
        assertThat(VendorCategory.CLOUD_PROVIDER.wireForm()).isEqualTo("cloud_provider");
        assertThat(VendorCategory.IDENTITY.wireForm()).isEqualTo("identity");
        assertThat(VendorCategory.ITSM.wireForm()).isEqualTo("itsm");
        assertThat(VendorCategory.SIEM.wireForm()).isEqualTo("siem");
        assertThat(VendorCategory.EDR.wireForm()).isEqualTo("edr");
        assertThat(VendorCategory.NOTIFICATION.wireForm()).isEqualTo("notification");
        assertThat(VendorCategory.OTHER.wireForm()).isEqualTo("other");
    }

    @Test
    void fromWireForm_shouldResolveAllSevenConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(VendorCategory.fromWireForm("cloud_provider")).contains(VendorCategory.CLOUD_PROVIDER);
        assertThat(VendorCategory.fromWireForm("identity")).contains(VendorCategory.IDENTITY);
        assertThat(VendorCategory.fromWireForm("itsm")).contains(VendorCategory.ITSM);
        assertThat(VendorCategory.fromWireForm("siem")).contains(VendorCategory.SIEM);
        assertThat(VendorCategory.fromWireForm("edr")).contains(VendorCategory.EDR);
        assertThat(VendorCategory.fromWireForm("notification")).contains(VendorCategory.NOTIFICATION);
        assertThat(VendorCategory.fromWireForm("other")).contains(VendorCategory.OTHER);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<VendorCategory> result = VendorCategory.fromWireForm("not-a-real-category");

        // Assert
        assertThat(result).isEmpty();
    }
}
