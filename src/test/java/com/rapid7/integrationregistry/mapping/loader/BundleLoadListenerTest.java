package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.testsupport.StubVendorMappingSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleLoadListenerTest {

    private S3VendorMappingBundleLoader loader;
    private VendorMappingSnapshotHolder holder;
    private VendorMappingProperties properties;
    private BundleLoadListener listener;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        loader = mock(S3VendorMappingBundleLoader.class);
        holder = mock(VendorMappingSnapshotHolder.class);
        properties = new VendorMappingProperties("v1.0.0", "test-bucket", "registry/mappings/", Path.of("/tmp"));
        listener = new BundleLoadListener(loader, holder, properties);

        logger = (Logger) LoggerFactory.getLogger(BundleLoadListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void onApplicationEvent_shouldPopulateHolder_whenLoadSucceeds() throws Exception {
        // Arrange
        VendorMappingSnapshot loaded = StubVendorMappingSnapshot.returningUnknown("v1.0.0");
        when(loader.load()).thenReturn(loaded);

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert — the holder receives a decorator wrapping the loaded snapshot.
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
            .contains("readiness will report DOWN")
            .contains("BundleLoadException")
            .contains("v1.0.0")
            .contains("test-bucket")
            .contains("registry/mappings/vendor-mapping-v1.0.0.tgz");
    }

    @Test
    void onApplicationEvent_shouldLogInfo_whenLoadSucceeds() throws Exception {
        // Arrange
        when(loader.load()).thenReturn(StubVendorMappingSnapshot.returningUnknown("v1.0.0"));

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

    @Test
    void onApplicationEvent_shouldAbsorbRuntimeException_whenLoaderThrowsUnchecked() throws Exception {
        // Arrange — simulate a misconfigured/buggy collaborator throwing
        // an unchecked exception that the listener must NOT propagate.
        when(loader.load()).thenThrow(new NullPointerException("S3Client returned null"));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert — holder is not populated; ERROR log captures the unchecked failure.
        verify(holder, never()).set(any());

        ILoggingEvent errorEvent = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected ERROR log event"));
        assertThat(errorEvent.getFormattedMessage())
            .contains("Vendor mapping bundle load failed")
            .contains("NullPointerException")
            .contains("S3Client returned null");
    }

    @Test
    void onApplicationEvent_shouldSkipLoad_whenHolderAlreadyLoaded() throws Exception {
        // Arrange — defensive guard against re-firing the event in tests.
        when(holder.isLoaded()).thenReturn(true);

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert — no load attempt, no holder mutation.
        verify(loader, never()).load();
        verify(holder, never()).set(any());
    }
}
