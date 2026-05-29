package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Orchestrator sub-object of {@link ConnectionViewModel}. {@code status}
 * values: {@code healthy | error | warning | stopped | unknown}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Orchestrator(String id, String name, String status, String version) {}
