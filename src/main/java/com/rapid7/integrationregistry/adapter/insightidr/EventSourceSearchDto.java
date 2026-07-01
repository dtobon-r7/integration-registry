package com.rapid7.integrationregistry.adapter.insightidr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of one {@code /eventsources/search} result row. No health fields — those come from
 * the per-source detail call. {@code id} keys the detail call and the {@code configuration_url}
 * template; {@code productType} is the preferred source identifier ({@code productName} is the
 * legacy fallback); {@code name} becomes {@code integration_label}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSourceSearchDto(
    String id, String rrn, String name, String productType, String productName) {}
