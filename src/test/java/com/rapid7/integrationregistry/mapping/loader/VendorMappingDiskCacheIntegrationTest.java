package com.rapid7.integrationregistry.mapping.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Integration test asserting same-version-restart cache behavior: when a cached {@code .tgz} exists
 * at boot, the loader reads from disk and never touches S3. Pre-seeds the cache file BEFORE
 * Spring's context bootstrap via a static {@code @BeforeAll}, then registers the temp dir into
 * {@code integration-registry.vendor-mapping.cache-dir} via {@code @DynamicPropertySource}.
 */
@SpringBootTest
class VendorMappingDiskCacheIntegrationTest {

  private static Path cacheDir;
  private static Path cacheFile;

  @BeforeAll
  static void seedCacheBeforeContextBootstrap() throws IOException {
    cacheDir = Files.createTempDirectory("vendor-mapping-cache-test-");
    cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0.tgz");

    byte[] mvpSeedYaml;
    try (InputStream stream =
        VendorMappingDiskCacheIntegrationTest.class.getResourceAsStream(
            "/vendor-mapping/bundle/mvp-seed.yaml")) {
      assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
      mvpSeedYaml = stream.readAllBytes();
    }
    byte[] tgz = BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml");
    Files.write(cacheFile, tgz);
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
    registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
    registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "registry/mappings/");
    registry.add("integration-registry.vendor-mapping.cache-dir", () -> cacheDir.toString());
    // InsightConnect adapter properties are required (no defaults) and bound at context
    // startup; supply them so the full application context loads. This test does not
    // exercise the ICON adapter.
    registry.add("integration-registry.insightconnect.base-url", () -> "http://icon.test.local");
    registry.add("integration-registry.insightconnect.icon-base", () -> "http://icon.test.local");
    // InsightIDR base-url/idr-base likewise have no defaults; supply them so the context loads.
    // This test does not exercise the IDR adapter.
    registry.add("integration-registry.insightidr.base-url", () -> "http://idr.test.local");
    registry.add("integration-registry.insightidr.idr-base", () -> "http://idr.test.local");
  }

  @MockitoBean S3Client s3Client;

  @Autowired BundleLoadHealthIndicator healthIndicator;

  @Autowired VendorMappingSnapshot vendorMappingSnapshot;

  @Test
  void readinessHealthIndicator_shouldReportUp_whenCacheIsPresent() {
    Health health = healthIndicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("mapping_version", "v1.0.0")
        .containsEntry("bundle_version", "v1.0.0");
  }

  @Test
  void s3Client_shouldNotBeCalled_whenCacheIsPresent() {
    verify(s3Client, never())
        .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
  }

  @Test
  void mappingVersion_shouldComeFromCachedBundle() {
    assertThat(vendorMappingSnapshot.mappingVersion()).isEqualTo("v1.0.0");
  }
}
