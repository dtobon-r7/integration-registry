package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionRecordsTest {

    private static final String VS_ID = "microsoft-defender";
    private static final String VS_NAME = "Microsoft Defender";
    private static final String VENDOR_ID = "microsoft";
    private static final String VENDOR_NAME = "Microsoft";
    private static final VendorCategory VENDOR_CATEGORY = VendorCategory.EDR;
    private static final IntegrationStatus AGGREGATE_HEALTH = IntegrationStatus.HEALTHY;
    private static final Instant LAST_UPDATED = Instant.parse("2026-05-29T10:00:00Z");

    @Nested
    class IntegrationTypeCountTest {

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 1);

            // Assert
            assertThat(record.integrationType()).isEqualTo("SIEM Event Source");
            assertThat(record.total()).isEqualTo(4);
            assertThat(record.errorCount()).isEqualTo(1);
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
            assertNpeFromCtor(
                () -> new IntegrationTypeCount(null, 4, 1),
                IntegrationTypeCount.FIELD_INTEGRATION_TYPE);
        }

        @Test
        void constructor_shouldThrowIAE_whenTotalNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", -1, 0))
                .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
        }

        @Test
        void constructor_shouldThrowIAE_whenErrorCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 4, -1))
                .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT);
        }

        @Test
        void constructor_shouldThrowIAE_whenErrorCountExceedsTotal() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 2, 3))
                .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT)
                .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
        }

        @Test
        void constructor_shouldAccept_whenErrorCountEqualsTotal() {
            // Arrange — boundary case: every instance is in error
            // Act
            IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 4);

            // Assert
            assertThat(record.errorCount()).isEqualTo(4);
        }
    }

    @Nested
    class VendorCardTest {

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            VendorCard record = new VendorCard(VENDOR_ID, VENDOR_NAME, 3);

            // Assert
            assertThat(record.vendorId()).isEqualTo(VENDOR_ID);
            assertThat(record.vendorName()).isEqualTo(VENDOR_NAME);
            assertThat(record.vendorServicesCount()).isEqualTo(3);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorIdNull() {
            assertNpeFromCtor(
                () -> new VendorCard(null, VENDOR_NAME, 3),
                VendorCard.FIELD_VENDOR_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorNameNull() {
            assertNpeFromCtor(
                () -> new VendorCard(VENDOR_ID, null, 3),
                VendorCard.FIELD_VENDOR_NAME);
        }

        @Test
        void constructor_shouldThrowIAE_whenVendorServicesCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VendorCard(VENDOR_ID, VENDOR_NAME, -1))
                .withMessageContaining(VendorCard.FIELD_VENDOR_SERVICES_COUNT);
        }
    }

    @Nested
    class IntegrationDetailTest {

        private static final String INTEGRATION_ID = "es_1234";
        private static final String DATA_SOURCE_ID = "insightidr|product_type|microsoft-defender-endpoint";
        private static final String CONFIGURATION_URL =
            "https://idr.example/eventsources/es_1234";

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, "my-defender",
                IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL);

            // Assert
            assertThat(record.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(record.dataSourceId()).isEqualTo(DATA_SOURCE_ID);
            assertThat(record.integrationLabel()).isEqualTo("my-defender");
            assertThat(record.status()).isEqualTo(IntegrationStatus.HEALTHY);
            assertThat(record.lastSuccessTimestamp()).isEqualTo(LAST_UPDATED);
            assertThat(record.configurationUrl()).isEqualTo(CONFIGURATION_URL);
        }

        @Test
        void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
            // Arrange — RFC-001: integrationLabel is nullable when the source product
            // exposes no per-instance customer-given name (e.g. ICON connections).

            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, null,
                IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL);

            // Assert
            assertThat(record.integrationLabel()).isNull();
        }

        @Test
        void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, "my-defender",
                IntegrationStatus.HEALTHY, null, CONFIGURATION_URL);

            // Assert
            assertThat(record.lastSuccessTimestamp()).isNull();
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationIdNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(null, DATA_SOURCE_ID, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_INTEGRATION_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenDataSourceIdNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, null, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_DATA_SOURCE_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenStatusNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, DATA_SOURCE_ID, "x",
                    null, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_STATUS);
        }

        @Test
        void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, DATA_SOURCE_ID, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, null),
                IntegrationDetail.FIELD_CONFIGURATION_URL);
        }
    }

    private static void assertNpeFromCtor(ThrowingCallable ctor, String expectedField) {
        assertThatNullPointerException().isThrownBy(ctor).withMessage(expectedField);
    }
}
