/**
 * InsightIDR adapter: fetches Microsoft Defender event sources via the two-call pattern — {@code
 * GET /api/3/organizations/{orgId}/eventsources/search} then one {@code
 * /api/3/organizations/{orgId}/eventsources/{id}} detail call per source — and normalizes them to
 * {@link com.rapid7.integrationregistry.adapter.NormalizedIntegration} records.
 *
 * <p>Per ADR-002 the outbound calls use Spring {@code RestClient} (not the reactive {@code
 * WebClient} named in RFC-001); the N+1 detail fan-out is bounded inside the adapter via {@link
 * com.rapid7.integrationregistry.adapter.insightidr.BoundedDetailFetcher}, leaving
 * coordinator-level fan-out (T07) untouched. Health derivation lives in the pure {@link
 * com.rapid7.integrationregistry.adapter.insightidr.EventSourceStatusMapper}; the DTO records
 * mirror the real {@code cloud-monitoring-service-ui} wire shape.
 */
package com.rapid7.integrationregistry.adapter.insightidr;
