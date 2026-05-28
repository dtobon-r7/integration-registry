/**
 * Checked failure modes thrown by
 * {@link com.rapid7.integrationregistry.adapter.IntegrationAdapter#fetch}.
 * All concrete types extend
 * {@link com.rapid7.integrationregistry.adapter.exception.AdapterException},
 * the abstract family parent that declares
 * {@link com.rapid7.integrationregistry.adapter.exception.AdapterException#isTransient()}
 * and
 * {@link com.rapid7.integrationregistry.adapter.exception.AdapterException#reasonCode()}.
 * Concrete subclasses remain distinguishable so the fan-out coordinator can
 * map each to a distinct {@code unavailable_products[].reason} (RFC-001).
 *
 * <p>See ADR-001 for the family-parent rule and the per-subclass values
 * for {@code isTransient()} / {@code reasonCode()}.
 */
package com.rapid7.integrationregistry.adapter.exception;
