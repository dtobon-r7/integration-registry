package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level response from {@code GET /api/public/v1/connections}. The adapter
 * reads {@code data}; {@code metadata.total} is carried for completeness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionsResponse(List<ConnectionViewModel> data, ResponseMetadata metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseMetadata(Integer total) {}
}
