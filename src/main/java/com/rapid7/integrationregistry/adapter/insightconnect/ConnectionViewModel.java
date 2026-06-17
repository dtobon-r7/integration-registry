package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A single ICON automation connection. Field names match komand's wire shape exactly: the embedded
 * test array is {@code tests} (verified against live komand: {@code conntest.ConnectionViewModel}
 * embeds {@code Tests json:"tests"}). {@code configurationUrl} is nullable — today's API does not
 * return it, but the adapter prefers it when present (RFC-001 forward-compat clause).
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
    List<ConnectionTest> tests,
    String configurationUrl) {}
