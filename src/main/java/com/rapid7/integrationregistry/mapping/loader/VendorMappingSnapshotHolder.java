package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot {@link AtomicReference}-backed wrapper exposed as the
 * {@link VendorMappingSnapshot} bean. The runtime loader populates the
 * reference exactly once during boot via {@link #set(VendorMappingSnapshot)};
 * pre-population reads throw {@link IllegalStateException}.
 *
 * <p>The readiness gate is the upstream contract that prevents pre-load reads
 * in production. The {@code IllegalStateException} is a defensive failure
 * mode for the case where the gate is bypassed (e.g., a misconfigured probe).
 */
final class VendorMappingSnapshotHolder implements VendorMappingSnapshot {

    private static final String NOT_LOADED_MESSAGE =
        "snapshot not yet loaded — readiness should have prevented this call";
    private static final String FIELD_SNAPSHOT = "snapshot";

    private final AtomicReference<VendorMappingSnapshot> ref = new AtomicReference<>();

    void set(VendorMappingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, FIELD_SNAPSHOT);
        if (!ref.compareAndSet(null, snapshot)) {
            throw new IllegalStateException("snapshot already set; holder is one-shot");
        }
    }

    /**
     * Non-throwing check used by the readiness {@code HealthIndicator} to
     * report DOWN before the bundle has been loaded. Distinct from
     * {@link #lookup} / {@link #mappingVersion}, which throw to signal a
     * misconfigured probe.
     */
    boolean isLoaded() {
        return ref.get() != null;
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        return current().lookup(productName, sourceType, sourceValue);
    }

    @Override
    public String mappingVersion() {
        return current().mappingVersion();
    }

    private VendorMappingSnapshot current() {
        VendorMappingSnapshot snapshot = ref.get();
        if (snapshot == null) {
            throw new IllegalStateException(NOT_LOADED_MESSAGE);
        }
        return snapshot;
    }
}
