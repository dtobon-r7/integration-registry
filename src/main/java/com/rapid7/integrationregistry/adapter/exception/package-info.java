/**
 * Checked failure modes thrown by
 * {@link com.rapid7.integrationregistry.adapter.IntegrationAdapter#fetch}. Each type is
 * independently catchable so the future fan-out coordinator can map each to a distinct
 * {@code unavailable_products[].reason} (RFC-001).
 */
package com.rapid7.integrationregistry.adapter.exception;
