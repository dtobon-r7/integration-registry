package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BundleLoadListenerTest {

    private S3VendorMappingBundleLoader loader;
    private VendorMappingSnapshotHolder holder;
    private VendorMappingProperties properties;
    private ApplicationEventPublisher events;
    private BundleLoadListener listener;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        loader = mock(S3VendorMappingBundleLoader.class);
        holder = mock(VendorMappingSnapshotHolder.class);
        properties = new VendorMappingProperties("v1.0.0", "test-bucket", "registry/mappings/", Path.of("/tmp"));
        events = mock(ApplicationEventPublisher.class);
        listener = new BundleLoadListener(loader, holder, properties, events);

        logger = (Logger) LoggerFactory.getLogger(BundleLoadListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static VendorMappingSnapshot stubSnapshot(String version) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return VendorResolution.unknown();
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void onApplicationEvent_shouldPublishRefusing_thenAccepting_whenLoadSucceeds() throws Exception {
        // Arrange
        when(loader.load()).thenReturn(stubSnapshot("v1.0.0"));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        InOrder eventOrder = inOrder(events, holder);
        ArgumentCaptor<AvailabilityChangeEvent> firstEvent = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        ArgumentCaptor<AvailabilityChangeEvent> secondEvent = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        eventOrder.verify(events).publishEvent(firstEvent.capture());
        eventOrder.verify(holder).set(any(VendorMappingSnapshot.class));
        eventOrder.verify(events).publishEvent(secondEvent.capture());

        assertThat(firstEvent.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(secondEvent.getValue().getState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void onApplicationEvent_shouldPublishRefusing_only_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("boom")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ArgumentCaptor<AvailabilityChangeEvent> capture = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(events).publishEvent(capture.capture());   // exactly once
        assertThat(capture.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        verifyNoInteractions(holder);
    }

    @Test
    void onApplicationEvent_shouldPopulateHolder_whenLoadSucceeds() throws Exception {
        // Arrange
        VendorMappingSnapshot loaded = stubSnapshot("v1.0.0");
        when(loader.load()).thenReturn(loaded);

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert — the holder receives a decorator wrapping the loaded snapshot,
        // so the captured argument is a LoggingVendorMappingSnapshot whose
        // mappingVersion() delegates to the loaded snapshot's "v1.0.0".
        ArgumentCaptor<VendorMappingSnapshot> setCaptor =
            ArgumentCaptor.forClass(VendorMappingSnapshot.class);
        verify(holder).set(setCaptor.capture());
        VendorMappingSnapshot setSnapshot = setCaptor.getValue();
        assertThat(setSnapshot).isInstanceOf(LoggingVendorMappingSnapshot.class);
        assertThat(setSnapshot.mappingVersion()).isEqualTo("v1.0.0");
    }

    @Test
    void onApplicationEvent_shouldNotPopulateHolder_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("boom")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        verify(holder, never()).set(any());
    }

    @Test
    void onApplicationEvent_shouldLogStructuredError_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("connection reset")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ILoggingEvent errorEvent = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected ERROR log event"));
        String formatted = errorEvent.getFormattedMessage();
        assertThat(formatted)
            .contains("Vendor mapping bundle load failed")
            .contains("readiness will remain REFUSING_TRAFFIC")
            .contains("BundleLoadException")
            .contains("v1.0.0")
            .contains("test-bucket")
            .contains("registry/mappings/vendor-mapping-v1.0.0.tgz");
    }

    @Test
    void onApplicationEvent_shouldLogInfo_whenLoadSucceeds() throws Exception {
        // Arrange
        when(loader.load()).thenReturn(stubSnapshot("v1.0.0"));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ILoggingEvent infoEvent = appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected INFO log event"));
        String formatted = infoEvent.getFormattedMessage();
        assertThat(formatted)
            .contains("Vendor mapping bundle loaded")
            .contains("mapping_version=v1.0.0")
            .contains("bundle_version=v1.0.0");
    }
}
