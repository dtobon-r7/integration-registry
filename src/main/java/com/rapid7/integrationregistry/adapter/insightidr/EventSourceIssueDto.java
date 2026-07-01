package com.rapid7.integrationregistry.adapter.insightidr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of the live {@code EventSourceIssueDto} ({@code cloud-monitoring-service-ui}). Only
 * the fields the registry reads for health derivation are modeled. {@code severity} drives the
 * error-vs-warning split when an issue is present.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSourceIssueDto(String severity, String message, Long eventTime) {}
