package com.rapid7.integrationregistry.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

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
    // Act
    NormalizedIntegration record =
        new NormalizedIntegration(
            INTEGRATION_ID,
            SOURCE_IDENTIFIER,
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            "my-jira",
            STATUS,
            LAST_SUCCESS,
            CONFIGURATION_URL,
            CUSTOMER_ACCOUNT_ID);

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
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                null,
                SOURCE_IDENTIFIER,
                PRODUCT_NAME,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_INTEGRATION_ID);
  }

  @Test
  void constructor_shouldThrowNPE_whenSourceIdentifierNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                null,
                PRODUCT_NAME,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_SOURCE_IDENTIFIER);
  }

  @Test
  void constructor_shouldThrowNPE_whenProductNameNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                SOURCE_IDENTIFIER,
                null,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_PRODUCT_NAME);
  }

  @Test
  void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                SOURCE_IDENTIFIER,
                PRODUCT_NAME,
                null,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_INTEGRATION_TYPE);
  }

  @Test
  void constructor_shouldThrowNPE_whenStatusNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                SOURCE_IDENTIFIER,
                PRODUCT_NAME,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                null,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_STATUS);
  }

  @Test
  void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                SOURCE_IDENTIFIER,
                PRODUCT_NAME,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                null,
                CUSTOMER_ACCOUNT_ID),
        NormalizedIntegration.FIELD_CONFIGURATION_URL);
  }

  @Test
  void constructor_shouldThrowNPE_whenCustomerAccountIdNull() {
    assertNpeFromCtor(
        () ->
            new NormalizedIntegration(
                INTEGRATION_ID,
                SOURCE_IDENTIFIER,
                PRODUCT_NAME,
                INTEGRATION_TYPE,
                INTEGRATION_LABEL,
                STATUS,
                LAST_SUCCESS,
                CONFIGURATION_URL,
                null),
        NormalizedIntegration.FIELD_CUSTOMER_ACCOUNT_ID);
  }

  @Test
  void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
    // Arrange
    // RFC-001: integrationLabel is nullable when the source product has no per-instance label

    // Act
    NormalizedIntegration record =
        new NormalizedIntegration(
            INTEGRATION_ID,
            SOURCE_IDENTIFIER,
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            null,
            STATUS,
            LAST_SUCCESS,
            CONFIGURATION_URL,
            CUSTOMER_ACCOUNT_ID);

    // Assert
    assertThat(record.integrationLabel()).isNull();
  }

  @Test
  void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
    // Arrange
    // RFC-001: null when no successful activity recorded

    // Act
    NormalizedIntegration record =
        new NormalizedIntegration(
            INTEGRATION_ID,
            SOURCE_IDENTIFIER,
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            INTEGRATION_LABEL,
            STATUS,
            null,
            CONFIGURATION_URL,
            CUSTOMER_ACCOUNT_ID);

    // Assert
    assertThat(record.lastSuccessTimestamp()).isNull();
  }

  private static void assertNpeFromCtor(ThrowingCallable ctor, String expectedField) {
    assertThatNullPointerException().isThrownBy(ctor).withMessage(expectedField);
  }
}
