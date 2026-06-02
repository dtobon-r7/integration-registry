/**
 * Request-time fan-out: parallel adapter dispatch, per-adapter timeouts, a total request deadline,
 * failure isolation, and the stale-tier fallback decision tree against the two-tier cache. {@link
 * com.rapid7.integrationregistry.coordinator.ProductOutcome} is the structured per-product output
 * consumed by the read API (T09).
 */
package com.rapid7.integrationregistry.coordinator;
