/**
 * Vendor-mapping read-side contract: the {@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot}
 * interface, the {@link com.rapid7.integrationregistry.mapping.VendorResolution} record carrying resolved
 * vendor/vendor-service identity, and the closed enums
 * ({@link com.rapid7.integrationregistry.mapping.VendorCategory},
 * {@link com.rapid7.integrationregistry.mapping.SourceType},
 * {@link com.rapid7.integrationregistry.mapping.ProductName}) referenced by the bundle JSON Schema and
 * by the snapshot lookup API.
 *
 * <p>Implementations of {@code VendorMappingSnapshot} live in this package (Plan 02);
 * no other internal Registry layer may depend on this package other than {@code aggregator}
 * (enforced by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
