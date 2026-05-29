package com.rapid7.integrationregistry.mapping.loader;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * HTTP-level readiness probe test. Asserts via {@code RestClient} that the actual {@code
 * /actuator/health/readiness} JSON exposes the {@code bundleLoad} component (the indicator bean's
 * group registration name) once the bundle has loaded successfully.
 *
 * <p>This is the only test in the suite that exercises the probe through the HTTP boundary; the
 * other integration tests assert the indicator bean directly. Hitting HTTP catches a subtle
 * regression class: Spring Boot's {@code HealthContributorNameGenerator} strips the {@code
 * HealthIndicator} suffix when registering bean names into health groups, so the bean {@code
 * bundleLoadHealthIndicator} surfaces in the JSON as {@code bundleLoad}. If a future framework
 * upgrade changed that suffix-stripping rule the application.yaml's {@code group.readiness.include:
 * ...,bundleLoad} would silently match nothing — the readiness gate would fail open and ship
 * traffic to a holder-empty replica. Asserting the JSON shape here pins the contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VendorMappingReadinessProbeIntegrationTest {

  private static Path cacheDir;

  @BeforeAll
  static void seedCacheBeforeContextBootstrap() throws IOException {
    cacheDir = Files.createTempDirectory("vendor-mapping-readiness-probe-test-");
    Path cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0.tgz");
    byte[] mvpSeedYaml;
    try (InputStream stream =
        VendorMappingReadinessProbeIntegrationTest.class.getResourceAsStream(
            "/vendor-mapping/bundle/mvp-seed.yaml")) {
      assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
      mvpSeedYaml = stream.readAllBytes();
    }
    Files.write(cacheFile, BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml"));
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
    registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
    registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "registry/mappings/");
    registry.add("integration-registry.vendor-mapping.cache-dir", () -> cacheDir.toString());
    // /actuator/health groups expose component details only when show-details is enabled.
    registry.add("management.endpoint.health.show-details", () -> "always");
    // InsightConnect adapter properties are required (no defaults) and bound at context
    // startup; supply them so the full application context loads. This test does not
    // exercise the ICON adapter.
    registry.add("integration-registry.insightconnect.base-url", () -> "http://icon.test.local");
    registry.add("integration-registry.insightconnect.icon-base", () -> "http://icon.test.local");
  }

  @MockitoBean S3Client s3Client;

  @LocalServerPort int port;

  @Test
  @SuppressWarnings("unchecked")
  void readinessProbe_shouldExposeBundleLoadComponent_overHttp() {
    // Arrange — RestClient pointed at the random server port.
    RestClient client = RestClient.create("http://localhost:" + port);

    // Act — fetch the readiness JSON.
    Map<String, Object> response =
        client.get().uri("/actuator/health/readiness").retrieve().body(Map.class);

    // Assert — the bundleLoad component must appear in the components map.
    // Spring's HealthContributorNameGenerator strips the HealthIndicator
    // suffix; if that contract changes upstream this assertion fails fast.
    assertThat(response).isNotNull();
    assertThat(response).containsKey("status");
    assertThat(response).containsKey("components");
    Map<String, Object> components = (Map<String, Object>) response.get("components");
    assertThat(components)
        .as(
            "readiness group must include bundleLoad component "
                + "(Spring's HealthContributorNameGenerator strips HealthIndicator suffix)")
        .containsKey("bundleLoad");
  }
}
