package com.rapid7.integrationregistry.mapping;

public interface VendorMappingSnapshot {

    /**
     * Resolve a raw product source identifier to its canonical vendor / vendor-service
     * identity.
     *
     * @return resolution result for known triplets, or {@link VendorResolution#unknown()}
     *     for unmapped triplets — never null, never throws on unmapped input.
     * @throws NullPointerException if any argument is null
     */
    VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue);

    /**
     * The {@code metadata.mapping_version} of the loaded bundle (semver string,
     * e.g. {@code "v1.42.0"}). Surfaced on every API response so callers can
     * correlate results to a specific bundle revision.
     */
    String mappingVersion();
}
