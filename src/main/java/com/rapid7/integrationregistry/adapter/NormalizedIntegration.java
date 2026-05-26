package com.rapid7.integrationregistry.adapter;

import java.time.Instant;
import java.util.Objects;

public record NormalizedIntegration(
    String integrationId,
    SourceIdentifier sourceIdentifier,
    String productName,
    String integrationType,
    String integrationLabel,
    IntegrationStatus status,
    Instant lastSuccessTimestamp,
    String configurationUrl,
    String customerAccountId
) {

    static final String FIELD_INTEGRATION_ID = "integrationId";
    static final String FIELD_SOURCE_IDENTIFIER = "sourceIdentifier";
    static final String FIELD_PRODUCT_NAME = "productName";
    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_STATUS = "status";
    static final String FIELD_CONFIGURATION_URL = "configurationUrl";
    static final String FIELD_CUSTOMER_ACCOUNT_ID = "customerAccountId";

    public NormalizedIntegration {
        Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
        Objects.requireNonNull(sourceIdentifier, FIELD_SOURCE_IDENTIFIER);
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
        Objects.requireNonNull(customerAccountId, FIELD_CUSTOMER_ACCOUNT_ID);
    }
}
