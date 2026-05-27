/**
 * Vendor-mapping read-side contract and stateless data layer:
 *
 * <ul>
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot} interface
 *       (Plan 01) — the read-side contract.</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorResolution} record (Plan 01)
 *       carrying resolved vendor / vendor-service identity.</li>
 *   <li>The closed enums
 *       ({@link com.rapid7.integrationregistry.mapping.VendorCategory},
 *       {@link com.rapid7.integrationregistry.mapping.SourceType},
 *       {@link com.rapid7.integrationregistry.mapping.ProductName}) referenced by
 *       the bundle JSON Schema and the snapshot lookup API (Plan 01).</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.BundleParser} (Plan 02) —
 *       parses bundle YAML, validates against the schema, and constructs the
 *       immutable snapshot.</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.BundleParseException} (Plan 02) —
 *       checked exception covering YAML syntax and schema-validation failures.</li>
 * </ul>
 *
 * <p>The map-backed snapshot implementation is package-private; callers depend
 * on the {@code VendorMappingSnapshot} interface only.
 *
 * <p>No internal Registry layer may depend on this package other than
 * {@code aggregator} (enforced by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
