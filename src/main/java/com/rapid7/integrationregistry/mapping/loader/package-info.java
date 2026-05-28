/**
 * Runtime lifecycle of vendor mapping — Spring-wired loader that fetches the
 * pinned bundle from S3 at boot, caches on local disk for same-version
 * restarts, builds the immutable
 * {@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot}, exposes
 * it as a bean, and gates {@code /actuator/health/readiness} on successful
 * load.
 *
 * <p>This is the only sub-package within
 * {@code com.rapid7.integrationregistry.mapping} that imports Spring Framework
 * or the AWS SDK — the core data layer
 * ({@code com.rapid7.integrationregistry.mapping}) stays framework-agnostic.
 * The boundary is enforced by the
 * {@code mappingCoreLayer_shouldNotDependOnFrameworks} ArchUnit rule.
 *
 * <p>See RFC-001 §Vendor mapping → Bundle lifecycle and §Operational notes
 * for the ground truth this package wires up.
 */
package com.rapid7.integrationregistry.mapping.loader;
