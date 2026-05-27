package com.rapid7.integrationregistry.mapping;

import java.util.Map;
import java.util.Objects;

/**
 * Map-backed implementation of {@link VendorMappingSnapshot}. Constructed by
 * {@link BundleParser} from a parsed and schema-validated bundle; immutable
 * for the lifetime of the object.
 *
 * <p>Package-private — only {@code BundleParser} and tests within this package
 * construct instances. Callers outside this package depend on the
 * {@link VendorMappingSnapshot} interface.
 */
final class MapBackedVendorMappingSnapshot implements VendorMappingSnapshot {

    private static final String FIELD_PRODUCT_NAME = "productName";
    private static final String FIELD_SOURCE_TYPE = "sourceType";
    private static final String FIELD_SOURCE_VALUE = "sourceValue";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_MAPPING_VERSION = "mappingVersion";

    private record TripletKey(ProductName productName, SourceType sourceType, String sourceValue) {
        private TripletKey {
            Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
            Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
            Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
        }
    }

    private final Map<TripletKey, VendorResolution> index;
    private final String mappingVersion;

    MapBackedVendorMappingSnapshot(Map<TripletKey, VendorResolution> index, String mappingVersion) {
        Objects.requireNonNull(index, FIELD_INDEX);
        this.index = Map.copyOf(index);
        this.mappingVersion = Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        // Null validation lives in TripletKey's compact constructor (same FIELD_*
        // messages); duplicating the guards here would just deepen the stack frame.
        return index.getOrDefault(
            new TripletKey(productName, sourceType, sourceValue),
            VendorResolution.unknown());
    }

    @Override
    public String mappingVersion() {
        return mappingVersion;
    }

    /**
     * Package-private factory exposed to {@link BundleParser} (for index
     * construction) and to tests in this package — keeps {@code TripletKey}
     * fully encapsulated as an implementation detail.
     */
    static TripletKey key(ProductName productName, SourceType sourceType, String sourceValue) {
        return new TripletKey(productName, sourceType, sourceValue);
    }
}
