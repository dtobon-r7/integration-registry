package com.rapid7.integrationregistry.mapping.loader;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports the bundle-load state to {@code /actuator/health} (and the readiness
 * group) by consulting the {@link VendorMappingSnapshotHolder}. Returns:
 *
 * <ul>
 *   <li>{@code UP} with the loaded {@code mapping_version} once the holder has
 *       been populated.</li>
 *   <li>{@code DOWN} with the configured {@code bundle_version} when the
 *       holder is empty (bundle load not yet succeeded).</li>
 * </ul>
 *
 * <p>Registered in the readiness group via
 * {@code management.endpoint.health.group.readiness.include} so
 * {@code /actuator/health/readiness} aggregates this indicator's status with
 * the framework's {@code readinessState}. Any DOWN indicator pulls the group
 * to DOWN, regardless of {@code AvailabilityState}.
 *
 * <p>This is the readiness gate's load-bearing component. The
 * {@link BundleLoadListener} runs the load and populates the holder; this
 * indicator translates the holder's state into the probe response.
 */
final class BundleLoadHealthIndicator implements HealthIndicator {

    private static final String DETAIL_MAPPING_VERSION = "mapping_version";
    private static final String DETAIL_BUNDLE_VERSION = "bundle_version";
    private static final String DETAIL_REASON = "reason";
    private static final String REASON_NOT_LOADED = "vendor mapping bundle not yet loaded";

    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;

    BundleLoadHealthIndicator(VendorMappingSnapshotHolder holder, VendorMappingProperties properties) {
        this.holder = holder;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (holder.isLoaded()) {
            return Health.up()
                .withDetail(DETAIL_MAPPING_VERSION, holder.mappingVersion())
                .withDetail(DETAIL_BUNDLE_VERSION, properties.bundleVersion())
                .build();
        }
        return Health.down()
            .withDetail(DETAIL_REASON, REASON_NOT_LOADED)
            .withDetail(DETAIL_BUNDLE_VERSION, properties.bundleVersion())
            .build();
    }
}
