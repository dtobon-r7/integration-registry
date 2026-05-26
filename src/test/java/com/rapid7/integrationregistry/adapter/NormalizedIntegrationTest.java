package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NormalizedIntegrationTest {

    private static final String INTEGRATION_ID = "c_456";
    private static final SourceIdentifier SOURCE_IDENTIFIER =
        new SourceIdentifier("plugin_name", "jira");
    private static final String PRODUCT_NAME = "InsightConnect";
    private static final String INTEGRATION_TYPE = "Automation Plugin";
    private static final String INTEGRATION_LABEL = null;
    private static final IntegrationStatus STATUS = IntegrationStatus.HEALTHY;
    private static final Instant LAST_SUCCESS = Instant.parse("2026-05-26T10:00:00Z");
    private static final String CONFIGURATION_URL =
        "https://icon.example/automation/connections/c_456";
    private static final String CUSTOMER_ACCOUNT_ID = "org_abc";

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsProvided() {
        // Arrange
        // (constants above)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID,
            SOURCE_IDENTIFIER,
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            "my-jira",            // integrationLabel populated this time
            STATUS,
            LAST_SUCCESS,
            CONFIGURATION_URL,
            CUSTOMER_ACCOUNT_ID
        );

        // Assert
        assertThat(record.integrationId()).isEqualTo(INTEGRATION_ID);
        assertThat(record.sourceIdentifier()).isEqualTo(SOURCE_IDENTIFIER);
        assertThat(record.productName()).isEqualTo(PRODUCT_NAME);
        assertThat(record.integrationType()).isEqualTo(INTEGRATION_TYPE);
        assertThat(record.integrationLabel()).isEqualTo("my-jira");
        assertThat(record.status()).isEqualTo(STATUS);
        assertThat(record.lastSuccessTimestamp()).isEqualTo(LAST_SUCCESS);
        assertThat(record.configurationUrl()).isEqualTo(CONFIGURATION_URL);
        assertThat(record.customerAccountId()).isEqualTo(CUSTOMER_ACCOUNT_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                null, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_INTEGRATION_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenSourceIdentifierNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, null, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_SOURCE_IDENTIFIER);
    }

    @Test
    void constructor_shouldThrowNPE_whenProductNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, null, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_PRODUCT_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, null,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_INTEGRATION_TYPE);
    }

    @Test
    void constructor_shouldThrowNPE_whenStatusNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, null, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_STATUS);
    }

    @Test
    void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, null,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_CONFIGURATION_URL);
    }

    @Test
    void constructor_shouldThrowNPE_whenCustomerAccountIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                null))
            .withMessage(NormalizedIntegration.FIELD_CUSTOMER_ACCOUNT_ID);
    }

    @Test
    void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
        // Arrange
        // (RFC: ICON has no per-instance customer-given name, so integrationLabel is null)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
            null, STATUS, LAST_SUCCESS, CONFIGURATION_URL, CUSTOMER_ACCOUNT_ID);

        // Assert
        assertThat(record.integrationLabel()).isNull();
    }

    @Test
    void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
        // Arrange
        // (RFC: null when no successful activity recorded)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
            INTEGRATION_LABEL, STATUS, null, CONFIGURATION_URL, CUSTOMER_ACCOUNT_ID);

        // Assert
        assertThat(record.lastSuccessTimestamp()).isNull();
    }
}
