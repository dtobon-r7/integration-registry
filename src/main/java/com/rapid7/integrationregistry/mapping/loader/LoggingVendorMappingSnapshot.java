package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decorator that emits a WARN log on every unmapped-triplet lookup, including
 * the current {@code mapping_version}. The runtime loader wraps the parsed
 * {@link VendorMappingSnapshot} in this decorator before exposing it via the
 * holder bean — Plan 02's {@code MapBackedVendorMappingSnapshot} is left pure
 * (no logger, no Spring) so the layering boundary stays intact.
 */
final class LoggingVendorMappingSnapshot implements VendorMappingSnapshot {

    private static final Logger log = LoggerFactory.getLogger(LoggingVendorMappingSnapshot.class);
    private static final String FIELD_DELEGATE = "delegate";

    private final VendorMappingSnapshot delegate;

    LoggingVendorMappingSnapshot(VendorMappingSnapshot delegate) {
        this.delegate = Objects.requireNonNull(delegate, FIELD_DELEGATE);
    }

    // Identity check on VendorResolution.unknown(): the snapshot returns the
    // unknown() singleton for unmapped triplets, so reference equality is
    // sufficient and avoids a 5-field record-equals on the per-request hot path.
    // PMD's CompareObjectsWithEquals can't see the singleton invariant, so we
    // suppress locally.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        VendorResolution resolution = delegate.lookup(productName, sourceType, sourceValue);
        if (resolution == VendorResolution.unknown()) {
            log.warn("Unknown vendor mapping triplet: product={}, source_type={}, source_value={} (mapping_version={})",
                     productName, sourceType, sourceValue, delegate.mappingVersion());
        }
        return resolution;
    }

    @Override
    public String mappingVersion() {
        return delegate.mappingVersion();
    }
}
