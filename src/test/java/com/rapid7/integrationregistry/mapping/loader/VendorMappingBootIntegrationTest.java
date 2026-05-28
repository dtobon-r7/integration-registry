package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import com.rapid7.integrationregistry.testsupport.S3TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spring-context integration test covering the three boot scenarios:
 * valid bundle, S3 failure, invalid bundle. Each scenario uses
 * {@link DirtiesContext} to start with a fresh holder so the listener's
 * one-shot {@code set(...)} can run cleanly.
 *
 * <p>Readiness is asserted via the {@link BundleLoadHealthIndicator} directly
 * (not via {@code ApplicationAvailability}), because Spring Boot 4's framework
 * unconditionally publishes {@code ReadinessState.ACCEPTING_TRAFFIC} after all
 * {@code ApplicationReadyEvent} listeners run. The {@code HealthIndicator}
 * approach decouples the gate from that race.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class VendorMappingBootIntegrationTest {

    @TempDir
    static Path sharedTempDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
        registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
        registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "registry/mappings/");
        registry.add("integration-registry.vendor-mapping.cache-dir", () -> sharedTempDir.toString());
    }

    @MockitoBean
    S3Client s3Client;

    @Autowired
    BundleLoadHealthIndicator healthIndicator;

    @Autowired
    VendorMappingSnapshot vendorMappingSnapshot;

    @Autowired
    BundleLoadListener listener;

    private static byte[] mvpSeedYaml;

    @BeforeAll
    static void loadSeed() throws IOException {
        try (InputStream stream = VendorMappingBootIntegrationTest.class.getResourceAsStream(
                "/vendor-mapping/bundle/mvp-seed.yaml")) {
            assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
            mvpSeedYaml = stream.readAllBytes();
        }
    }

    /**
     * {@code @TempDir static Path sharedTempDir} persists across
     * {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} rebuilds, so a successful
     * load in one nested scenario leaves a valid cache file behind that the next
     * scenario's framework-fired listener invocation would happily read —
     * short-circuiting the S3-throws / invalid-bundle paths via
     * {@code holder.isLoaded()} once the manual re-fire runs. Wiping the cache
     * before each test guarantees every scenario starts cold. Outer-class
     * {@code @BeforeEach} runs before nested-class {@code @BeforeEach} per
     * JUnit 5 spec, so this lands first.
     */
    @BeforeEach
    void wipeCacheBeforeEachTest() throws IOException {
        Path cacheFile = sharedTempDir.resolve("vendor-mapping-v1.0.0.tgz");
        Files.deleteIfExists(cacheFile);
    }

    private static ApplicationStartedEvent dummyStartedEvent() {
        return new ApplicationStartedEvent(
            new SpringApplication(),
            new String[]{},
            null,
            Duration.ZERO);
    }

    /**
     * Captures Logback events from a target logger. Caller invokes
     * {@link #detach()} in {@code @AfterEach}. Used by the failure-path
     * scenarios below to assert ERROR log content.
     */
    static final class LogCapture {
        final Logger logger;
        final ListAppender<ILoggingEvent> appender;

        LogCapture(Class<?> loggerClass) {
            this.logger = (Logger) LoggerFactory.getLogger(loggerClass);
            this.appender = new ListAppender<>();
            this.appender.start();
            this.logger.addAppender(this.appender);
        }

        void detach() {
            logger.detachAppender(appender);
        }

        ILoggingEvent firstError() {
            return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected ERROR log event"));
        }
    }

    /**
     * The framework's own {@code ApplicationStartedEvent} fires before
     * {@code @MockitoBean} stubs are configured, so the listener's first
     * invocation hits an unstubbed mock and fails — leaving the holder empty.
     * Scenarios re-fire the event with stubs in place by calling
     * {@code listener.onApplicationEvent(dummyStartedEvent())} directly. The
     * listener's {@code holder.isLoaded()} guard means the second call after
     * a successful first run would be a no-op, but with {@code DirtiesContext}
     * each test gets a fresh holder so this isn't a concern in practice.
     */
    @Nested
    class WhenS3ReturnsValidBundle {

        @BeforeEach
        void stubS3WithValidBundle() {
            byte[] tgz = BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml");
            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenReturn(S3TestFixtures.responseBytesOf(tgz));
            listener.onApplicationEvent(dummyStartedEvent());
        }

        @Test
        void readinessHealthIndicator_shouldReportUp() {
            Health health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                .containsEntry("mapping_version", "v1.0.0")
                .containsEntry("bundle_version", "v1.0.0");
        }

        @Test
        void snapshot_shouldResolveAllFourMvpTriplets() {
            // Microsoft Defender via InsightIDR
            VendorResolution idrDefender = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
            assertThat(idrDefender.vendorServiceId()).isEqualTo("microsoft-defender");
            assertThat(idrDefender.vendorServiceName()).isEqualTo("Microsoft Defender");
            assertThat(idrDefender.vendorCategory()).isEqualTo(VendorCategory.EDR);
            assertThat(idrDefender.vendorId()).isEqualTo("microsoft");
            assertThat(idrDefender.vendorName()).isEqualTo("Microsoft");

            // Microsoft Defender via InsightConnect
            VendorResolution iconDefender = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "microsoft-defender");
            assertThat(iconDefender.vendorServiceId()).isEqualTo("microsoft-defender");

            // Jira via InsightConnect
            VendorResolution jira = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");
            assertThat(jira.vendorServiceId()).isEqualTo("jira");
            assertThat(jira.vendorId()).isEqualTo("atlassian");

            // Negative control: unmapped triplet returns the synthetic record.
            VendorResolution mystery = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "no-such-source");
            assertThat(mystery).isSameAs(VendorResolution.unknown());
        }

        @Test
        void mappingVersion_shouldBeV100() {
            assertThat(vendorMappingSnapshot.mappingVersion()).isEqualTo("v1.0.0");
        }
    }

    @Nested
    class WhenS3Throws {

        private LogCapture logs;

        @BeforeEach
        void stubS3WithFailureAndAttachAppender() {
            logs = new LogCapture(BundleLoadListener.class);
            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(SdkClientException.create("connection reset"));
            listener.onApplicationEvent(dummyStartedEvent());
        }

        @AfterEach
        void detachAppender() {
            logs.detach();
        }

        @Test
        void readinessHealthIndicator_shouldReportDown() {
            Health health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                .containsEntry("reason", "vendor mapping bundle not yet loaded")
                .containsEntry("bundle_version", "v1.0.0");
        }

        @Test
        void errorLog_shouldContainFailureClassAndS3Coordinates() {
            assertThat(logs.firstError().getFormattedMessage())
                .contains("BundleLoadException")
                .contains("test-bucket")
                .contains("registry/mappings/vendor-mapping-v1.0.0.tgz");
        }
    }

    @Nested
    class WhenS3ReturnsInvalidBundle {

        private LogCapture logs;

        @BeforeEach
        void stubS3WithInvalidBundleAndAttachAppender() {
            logs = new LogCapture(BundleLoadListener.class);

            // Bundle is structurally valid YAML but violates the schema:
            // source_value contains the reserved '|' character.
            String invalidYaml = """
                apiVersion: registry.rapid7.com/v1
                kind: VendorMapping
                metadata:
                  mapping_version: v1.0.0
                spec:
                  vendors:
                    - id: microsoft
                      name: Microsoft
                      services:
                        - id: microsoft-defender
                          name: Microsoft Defender
                          category: edr
                          data_sources:
                            - product: InsightIDR
                              source_type: product_type
                              source_value: "has|pipe"
                              display_name: Bad
                """;
            byte[] tgz = BundleArchiveBuilder.tgzOf(
                invalidYaml.getBytes(StandardCharsets.UTF_8), "vendor-mapping.yaml");
            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenReturn(S3TestFixtures.responseBytesOf(tgz));

            listener.onApplicationEvent(dummyStartedEvent());
        }

        @AfterEach
        void detachAppender() {
            logs.detach();
        }

        @Test
        void readinessHealthIndicator_shouldReportDown() {
            Health health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        void errorLog_shouldContainParseFailure() {
            assertThat(logs.firstError().getFormattedMessage())
                .contains("BundleLoadException")
                .contains("could not be parsed");
        }
    }
}
