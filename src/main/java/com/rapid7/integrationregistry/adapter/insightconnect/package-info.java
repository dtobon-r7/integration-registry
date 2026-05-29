/**
 * InsightConnect adapter: fetches automation connections from
 * {@code GET /api/public/v1/connections?includeTests=1} and normalizes them to
 * {@link com.rapid7.integrationregistry.adapter.NormalizedIntegration} records.
 *
 * <p>Per ADR-002 the outbound call uses Spring {@code RestClient} (not the
 * reactive {@code WebClient} named in RFC-001). Health derivation lives in the
 * pure {@link com.rapid7.integrationregistry.adapter.insightconnect.ConnectionStatusMapper};
 * the DTO records mirror the real ICON wire shape.
 */
package com.rapid7.integrationregistry.adapter.insightconnect;
