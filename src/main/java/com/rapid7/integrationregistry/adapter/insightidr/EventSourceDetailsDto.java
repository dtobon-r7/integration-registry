package com.rapid7.integrationregistry.adapter.insightidr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of an {@code /eventsources/{id}} detail response. {@code status} is a free-form
 * product string mapped to the 5-state model; {@code lastActive} is epoch milliseconds (nullable —
 * null means no successful activity ever); {@code issue} is present when the source has a reported
 * problem; {@code configurationUrl} is honored when the product returns one (null today → adapter
 * templates).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSourceDetailsDto(
    String status, Long lastActive, EventSourceIssueDto issue, String configurationUrl) {}
