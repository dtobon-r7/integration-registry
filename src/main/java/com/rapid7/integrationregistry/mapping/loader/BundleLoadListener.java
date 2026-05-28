package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

/**
 * Boot-time listener that wires the runtime lifecycle of vendor mapping.
 *
 * <p>Spring Boot 4 auto-publishes {@link ReadinessState#ACCEPTING_TRAFFIC}
 * when {@link ApplicationStartedEvent} fires. This listener overrides that
 * default by:
 *
 * <ol>
 *   <li>Publishing {@link ReadinessState#REFUSING_TRAFFIC} immediately on the
 *       same event.</li>
 *   <li>Loading the bundle (cache-first, S3 fallback).</li>
 *   <li>On success: wrapping the loaded snapshot in a
 *       {@link LoggingVendorMappingSnapshot} decorator, populating the
 *       {@link VendorMappingSnapshotHolder}, then publishing
 *       {@link ReadinessState#ACCEPTING_TRAFFIC}.</li>
 *   <li>On failure: logging a structured ERROR with failure class and
 *       bundle/S3 coordinates; readiness stays at
 *       {@code REFUSING_TRAFFIC} indefinitely so the replica is held out
 *       of rotation rather than serving with an empty snapshot.</li>
 * </ol>
 */
final class BundleLoadListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BundleLoadListener.class);

    private final S3VendorMappingBundleLoader loader;
    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;
    private final ApplicationEventPublisher events;

    BundleLoadListener(S3VendorMappingBundleLoader loader,
                       VendorMappingSnapshotHolder holder,
                       VendorMappingProperties properties,
                       ApplicationEventPublisher events) {
        this.loader = loader;
        this.holder = holder;
        this.properties = properties;
        this.events = events;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        VendorMappingSnapshot loaded;
        try {
            loaded = loader.load();
        } catch (BundleLoadException ex) {
            log.error("Vendor mapping bundle load failed; readiness will remain REFUSING_TRAFFIC. "
                    + "failure_class={} bundle_version={} s3_bucket={} s3_key={} cause={}",
                    ex.getClass().getSimpleName(),
                    properties.bundleVersion(),
                    properties.s3Bucket(),
                    properties.bundleObjectKey(),
                    ex.getMessage(), ex);
            return;
        }
        VendorMappingSnapshot decorated = new LoggingVendorMappingSnapshot(loaded);
        holder.set(decorated);
        log.info("Vendor mapping bundle loaded; mapping_version={} bundle_version={}",
                 decorated.mappingVersion(), properties.bundleVersion());
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
    }
}
