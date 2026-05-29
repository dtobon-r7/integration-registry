package com.rapid7.integrationregistry.mapping.loader;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports bundle-load state to the readiness probe by consulting the {@link
 * VendorMappingSnapshotHolder}. Registered in the readiness group via {@code
 * management.endpoint.health.group.readiness.include=bundleLoad} so any DOWN here pulls {@code
 * /actuator/health/readiness} to DOWN regardless of {@code AvailabilityState} — see {@link
 * BundleLoadListener} for why {@code AvailabilityState} alone is insufficient.
 */
final class BundleLoadHealthIndicator implements HealthIndicator {

  private static final String DETAIL_MAPPING_VERSION = "mapping_version";
  private static final String DETAIL_BUNDLE_VERSION = "bundle_version";
  private static final String DETAIL_REASON = "reason";
  private static final String REASON_NOT_LOADED = "vendor mapping bundle not yet loaded";

  private final VendorMappingSnapshotHolder holder;
  private final VendorMappingProperties properties;

  BundleLoadHealthIndicator(
      VendorMappingSnapshotHolder holder, VendorMappingProperties properties) {
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
