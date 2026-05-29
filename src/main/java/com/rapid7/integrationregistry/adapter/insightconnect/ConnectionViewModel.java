package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A single ICON automation connection. Field names match the wire shape
 * exactly ({@code connectionTests}, not {@code connection_test}).
 * {@code configurationUrl} is nullable — today's API does not return it, but
 * the adapter prefers it when present (RFC-001 forward-compat clause).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionViewModel(
    String id,
    String name,
    Plugin plugin,
    Orchestrator orchestrator,
    Boolean isCloud,
    String createdAt,
    String updatedAt,
    List<ConnectionTest> connectionTests,
    String configurationUrl
) {}
