/**
 * Checked failure modes thrown by the bundle pipeline at boot — YAML/Schema parse failures by
 * {@link com.rapid7.integrationregistry.mapping.BundleParser#parse} and S3-fetch / cache-IO /
 * archive-extract failures by the loader. Caught by the boot-time listener, which maps each failure
 * to a readiness-probe-down state plus a structured log entry.
 *
 * <p>The two exceptions in this package are payload-style per ADR-001: {@link BundleParseException}
 * carries a {@code Set<ValidationMessage>} payload for schema-validation failures; {@link
 * BundleLoadException} carries an {@code Optional<Path>} payload for cache-IO failures. Contrast
 * with the {@code adapter/exception/} family, which is marker-style.
 *
 * <p>This family deliberately has no shared parent class. With only two siblings and one consumer
 * (the boot-time listener), Java multi-catch handles the catch-as-group case. Adding a parent here
 * would be ceremony with no consumer — do not introduce one. See ADR-001 for the rationale.
 */
package com.rapid7.integrationregistry.mapping.exception;
