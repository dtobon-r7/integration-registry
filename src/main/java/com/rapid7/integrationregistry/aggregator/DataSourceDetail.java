package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.util.List;
import java.util.Objects;

/**
 * Per-data-source row for {@code GET /vendor-services/{id}} per RFC-001 §Data
 * Source entity. Carries a nested {@code integrations[]} of per-instance
 * detail. The {@code integrationsCount == integrations.size()} invariant is
 * enforced in the compact constructor — the field exists on the wire because
 * the RFC commits to it, but the two values cannot diverge.
 */
public record DataSourceDetail(
    String dataSourceId,
    String displayName,
    String integrationType,
    String productName,
    IntegrationStatus status,
    int integrationsCount,
    List<IntegrationDetail> integrations
) {

    static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
    static final String FIELD_DISPLAY_NAME = "displayName";
    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_PRODUCT_NAME = "productName";
    static final String FIELD_STATUS = "status";
    static final String FIELD_INTEGRATIONS_COUNT = "integrationsCount";
    static final String FIELD_INTEGRATIONS = "integrations";

    public DataSourceDetail {
        Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
        Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
        if (integrationsCount < 0) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_COUNT + " must be >= 0: " + integrationsCount);
        }
        if (integrationsCount != integrations.size()) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_COUNT + " (" + integrationsCount + ") must equal "
                    + FIELD_INTEGRATIONS + ".size() (" + integrations.size() + ")");
        }
        integrations = List.copyOf(integrations);
    }
}
