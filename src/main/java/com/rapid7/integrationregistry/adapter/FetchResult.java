package com.rapid7.integrationregistry.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FetchResult(
    List<NormalizedIntegration> integrations,
    Instant fetchedAt
) {

    static final String FIELD_INTEGRATIONS = "integrations";
    static final String FIELD_FETCHED_AT = "fetchedAt";

    public FetchResult {
        Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
        Objects.requireNonNull(fetchedAt, FIELD_FETCHED_AT);
        integrations = List.copyOf(integrations);
    }
}
