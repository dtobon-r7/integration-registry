package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorResolutionTest {

    private static final String VENDOR_SERVICE_ID = "microsoft-defender";
    private static final String VENDOR_SERVICE_NAME = "Microsoft Defender";
    private static final VendorCategory VENDOR_CATEGORY = VendorCategory.EDR;
    private static final String VENDOR_ID = "microsoft";
    private static final String VENDOR_NAME = "Microsoft";

    @Test
    void constructor_shouldBuildRecord_whenAllFiveFieldsProvided() {
        // Act
        VendorResolution resolution = new VendorResolution(
            VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME);

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo(VENDOR_SERVICE_ID);
        assertThat(resolution.vendorServiceName()).isEqualTo(VENDOR_SERVICE_NAME);
        assertThat(resolution.vendorCategory()).isEqualTo(VENDOR_CATEGORY);
        assertThat(resolution.vendorId()).isEqualTo(VENDOR_ID);
        assertThat(resolution.vendorName()).isEqualTo(VENDOR_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                null, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_SERVICE_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, null, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_SERVICE_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorCategoryNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, null, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_CATEGORY);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, null, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, null))
            .withMessage(VendorResolution.FIELD_VENDOR_NAME);
    }

    @Test
    void unknown_shouldReturnSyntheticTriplet_whenInvoked() {
        // Act
        VendorResolution unknown = VendorResolution.unknown();

        // Assert
        assertThat(unknown.vendorServiceId()).isEqualTo("unknown");
        assertThat(unknown.vendorServiceName()).isEqualTo("Unknown");
        assertThat(unknown.vendorCategory()).isEqualTo(VendorCategory.OTHER);
        assertThat(unknown.vendorId()).isEqualTo("unknown");
        assertThat(unknown.vendorName()).isEqualTo("Unknown");
    }

    @Test
    void unknown_shouldReturnSameInstance_whenInvokedTwice() {
        // Act
        VendorResolution first = VendorResolution.unknown();
        VendorResolution second = VendorResolution.unknown();

        // Assert
        assertThat(first).isSameAs(second);
    }
}
