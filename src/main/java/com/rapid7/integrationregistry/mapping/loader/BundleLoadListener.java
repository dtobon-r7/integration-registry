package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Boot-time listener that loads the vendor-mapping bundle and populates the snapshot holder.
 * Failure semantics are surfaced through the {@link BundleLoadHealthIndicator} on {@code
 * /actuator/health/readiness}, not through Spring's {@code AvailabilityChangeEvent} machinery.
 *
 * <p><b>Why not AvailabilityState:</b> Spring Boot 4's framework unconditionally publishes {@code
 * ReadinessState.ACCEPTING_TRAFFIC} from {@code EventPublishingRunListener.ready(...)}
 * <em>after</em> all {@code ApplicationReadyEvent} listeners have run. Any {@code REFUSING_TRAFFIC}
 * a listener publishes within the same {@code ready()} call is overwritten by the framework's
 * automatic {@code ACCEPTING_TRAFFIC} publish on a failed load. The custom {@code HealthIndicator}
 * bypasses that race entirely: it consults the holder at every probe call, so the readiness state
 * reflects the actual load outcome regardless of event ordering.
 */
final class BundleLoadListener implements ApplicationListener<ApplicationStartedEvent> {

  private static final Logger log = LoggerFactory.getLogger(BundleLoadListener.class);

  private final S3VendorMappingBundleLoader loader;
  private final VendorMappingSnapshotHolder holder;
  private final VendorMappingProperties properties;

  BundleLoadListener(
      S3VendorMappingBundleLoader loader,
      VendorMappingSnapshotHolder holder,
      VendorMappingProperties properties) {
    this.loader = loader;
    this.holder = holder;
    this.properties = properties;
  }

  // Catching RuntimeException is intentional and load-bearing: a startup
  // failure must never propagate out of onApplicationEvent and crash context
  // refresh. That would defeat the readiness gate by forcing the replica
  // into a hard-failed state instead of a held-out-of-rotation state. The
  // catch is narrow (a single try block guarding loader.load()) and Error
  // is deliberately allowed to propagate.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Override
  public void onApplicationEvent(ApplicationStartedEvent event) {
    if (holder.isLoaded()) {
      log.debug("Vendor mapping bundle already loaded; skipping load on repeat event");
      return;
    }
    VendorMappingSnapshot loaded;
    try {
      loaded = loader.load();
    } catch (BundleLoadException | RuntimeException ex) {
      log.error(
          "Vendor mapping bundle load failed; readiness will report DOWN. "
              + "failure_class={} bundle_version={} s3_bucket={} s3_key={} cause={}",
          ex.getClass().getSimpleName(),
          properties.bundleVersion(),
          properties.s3Bucket(),
          properties.bundleObjectKey(),
          ex.getMessage(),
          ex);
      return;
    }
    VendorMappingSnapshot decorated = new LoggingVendorMappingSnapshot(loaded);
    holder.set(decorated);
    log.info(
        "Vendor mapping bundle loaded; mapping_version={} bundle_version={}",
        decorated.mappingVersion(),
        properties.bundleVersion());
  }
}
