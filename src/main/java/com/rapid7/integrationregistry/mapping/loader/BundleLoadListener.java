package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Boot-time listener that loads the vendor-mapping bundle and populates the
 * snapshot holder. Failure semantics are surfaced through the
 * {@link BundleLoadHealthIndicator} on {@code /actuator/health/readiness},
 * not through Spring's {@code AvailabilityChangeEvent} machinery.
 *
 * <p><b>Why not AvailabilityState:</b> Spring Boot 4's framework unconditionally
 * publishes {@code ReadinessState.ACCEPTING_TRAFFIC} from
 * {@code EventPublishingRunListener.ready(...)} <em>after</em> all
 * {@code ApplicationReadyEvent} listeners have run. Any
 * {@code REFUSING_TRAFFIC} a listener publishes within the same {@code ready()}
 * call is overwritten by the framework's automatic {@code ACCEPTING_TRAFFIC}
 * publish on a failed load. The custom {@code HealthIndicator} bypasses that
 * race entirely: it consults the holder at every probe call, so the readiness
 * state reflects the actual load outcome regardless of event ordering.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Listener fires on {@link ApplicationStartedEvent} (after context
 *       refresh, before runners).</li>
 *   <li>Loads the bundle (cache-first, S3 fallback).</li>
 *   <li>On success: wraps the loaded snapshot in a
 *       {@link LoggingVendorMappingSnapshot} decorator and populates the
 *       {@link VendorMappingSnapshotHolder}. The {@link BundleLoadHealthIndicator}
 *       will then return UP for {@code /actuator/health/readiness}.</li>
 *   <li>On failure: logs a structured ERROR with failure class and bundle/S3
 *       coordinates; the holder stays empty. The {@link BundleLoadHealthIndicator}
 *       returns DOWN, holding the replica out of rotation. Both
 *       {@link BundleLoadException} and unchecked {@link RuntimeException}s
 *       are absorbed identically — a startup failure must never propagate
 *       out of {@code onApplicationEvent} and crash context refresh, since
 *       that would defeat the readiness gate by forcing the replica into a
 *       hard-failed state instead of a held-out-of-rotation state.</li>
 * </ol>
 */
final class BundleLoadListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BundleLoadListener.class);

    private final S3VendorMappingBundleLoader loader;
    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;

    BundleLoadListener(S3VendorMappingBundleLoader loader,
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
    // is deliberately allowed to propagate. PMD's AvoidCatchingGenericException
    // does not have visibility into this contract, so we suppress locally.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (holder.isLoaded()) {
            // Defensive: the listener should fire once per context. If it fires
            // again (e.g., a test re-publishing the event), skip — the holder
            // is one-shot and a second set() would throw. DEBUG-log so the
            // re-fire is observable in operational logs rather than silent.
            log.debug("Vendor mapping bundle already loaded; skipping load on repeat event");
            return;
        }
        VendorMappingSnapshot loaded;
        try {
            loaded = loader.load();
        } catch (BundleLoadException | RuntimeException ex) {
            log.error("Vendor mapping bundle load failed; readiness will report DOWN. "
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
    }
}
