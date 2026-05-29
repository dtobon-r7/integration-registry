package com.rapid7.integrationregistry.aggregator;

import java.util.Objects;

/**
 * Per-type aggregation under a vendor service per RFC-001 §Integration Types —
 * one entry per distinct {@code integration_type}. The surface is intentionally
 * narrow: only {@code total} and {@code errorCount}; no {@code warning_count} or
 * other state breakdowns. Adding more counts is a non-breaking forward extension.
 */
public record IntegrationTypeCount(
    String integrationType,
    int total,
    int errorCount
) {

    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_TOTAL = "total";
    static final String FIELD_ERROR_COUNT = "errorCount";

    public IntegrationTypeCount {
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        if (total < 0) {
            throw new IllegalArgumentException(FIELD_TOTAL + " must be >= 0: " + total);
        }
        if (errorCount < 0) {
            throw new IllegalArgumentException(FIELD_ERROR_COUNT + " must be >= 0: " + errorCount);
        }
        if (errorCount > total) {
            throw new IllegalArgumentException(
                FIELD_ERROR_COUNT + " (" + errorCount + ") must be <= "
                    + FIELD_TOTAL + " (" + total + ")");
        }
    }
}
