package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Embedded connection-test record from the ICON connections endpoint
 * (present when {@code ?includeTests=1}). Field names match the ICON wire
 * shape exactly. {@code createdAt} binds to {@link Instant} so most-recent
 * selection is a clean temporal comparison.
 *
 * <p>{@code status} values: {@code pending | success | failed | timeout}.
 * {@code isStale} may be absent in the JSON — a {@code null} is treated as
 * not-stale by {@link ConnectionStatusMapper}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionTest(
    String id,
    String connectionId,
    String status,
    Boolean isStale,
    String errorMessage,
    Instant createdAt
) {}
