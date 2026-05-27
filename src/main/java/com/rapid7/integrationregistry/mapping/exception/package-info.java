/**
 * Checked failure modes thrown by
 * {@link com.rapid7.integrationregistry.mapping.BundleParser#parse}. Each type
 * carries either YAML-parse causes or structured JSON Schema validation
 * messages. Caught by the bundle's boot-time loader, which maps the failure
 * to a readiness-probe-down state plus a structured log entry.
 *
 * <p>This is the <em>payload-style</em> exception family in the registry's
 * exception convention (see ADR-001 in the engagement's
 * {@code decisions/adr/}). Contrast with the {@code adapter/exception/}
 * family, which is marker-style.
 */
package com.rapid7.integrationregistry.mapping.exception;
