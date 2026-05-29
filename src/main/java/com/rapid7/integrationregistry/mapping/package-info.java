/**
 * Vendor-mapping read-side contract and stateless data layer:
 *
 * <ul>
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot} interface — the
 *       read-side contract.
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorResolution} record carrying resolved
 *       vendor / vendor-service identity.
 *   <li>The closed enums ({@link com.rapid7.integrationregistry.mapping.VendorCategory}, {@link
 *       com.rapid7.integrationregistry.mapping.SourceType}, {@link
 *       com.rapid7.integrationregistry.mapping.ProductName}) referenced by the bundle JSON Schema
 *       and the snapshot lookup API.
 *   <li>{@link com.rapid7.integrationregistry.mapping.BundleParser} — parses bundle YAML, validates
 *       against the schema, and constructs the immutable snapshot.
 *   <li>{@link com.rapid7.integrationregistry.mapping.exception.BundleParseException} — checked
 *       exception covering YAML syntax and schema-validation failures (lives in the {@code
 *       exception/} sub-package per ADR-001).
 * </ul>
 *
 * <p>The map-backed snapshot implementation is package-private; callers depend on the {@code
 * VendorMappingSnapshot} interface only.
 *
 * <p>No internal Registry layer may depend on this package other than {@code aggregator} (enforced
 * by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
