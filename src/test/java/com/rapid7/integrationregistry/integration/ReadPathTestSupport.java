package com.rapid7.integrationregistry.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.insightconnect.InsightConnectAdapter;
import com.rapid7.integrationregistry.cache.CacheKey;
import com.rapid7.integrationregistry.cache.CacheTier;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Shared boot wiring for the WP-04 full-context read-path suite: a full {@code @SpringBootTest}
 * (real controller/service/coordinator/aggregator/cache) against a Testcontainers Valkey (ADR-006,
 * via {@link com.rapid7.integrationregistry.cache.ValkeyTestContainer}), with the two adapters
 * faked by {@link StubAdapter} beans and {@code S3Client} mocked. A test-only multi-service
 * vendor-mapping bundle is staged on disk so the bundle loader resolves the seeded vendor services
 * without S3.
 */
// @Import is required because StubAdapterConfig is a nested @TestConfiguration of this abstract
// base, not of the concrete test class Spring bootstraps — Boot auto-detects nested test config
// only on the run test class itself, so an inherited nested config must be imported explicitly.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(ReadPathTestSupport.StubAdapterConfig.class)
@org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters(
    ReadPathTestSupport.ExcludeRealAdapters.class)
abstract class ReadPathTestSupport
    extends com.rapid7.integrationregistry.cache.ValkeyTestContainer {

  protected static final String ORG = "org-wp04";
  protected static final String USER = "user-wp04";
  protected static final String ORG_ID_HEADER = "X-IPIMS-ORG-ID";
  protected static final String USER_ID_HEADER = "X-IPIMS-USER-ID";
  protected static final String INSIGHT_IDR = "InsightIDR";
  protected static final String INSIGHT_CONNECT = "InsightConnect";

  private static Path cacheDir;

  /**
   * Supplies exactly the two stub adapters the coordinator autowires. The real {@code
   * InsightConnectAdapter} @Component is removed from the scan by {@link ExcludeRealAdapters} (see
   * fallback note there), so these two beans are the only {@code IntegrationAdapter}s in the
   * context. Both have hard-coded product names, so the coordinator's constructor-time validation
   * passes at boot with exactly {@code InsightConnect} + {@code InsightIDR}.
   */
  @TestConfiguration
  static class StubAdapterConfig {
    @Bean
    StubAdapter insightConnectAdapter() {
      return new StubAdapter(INSIGHT_CONNECT);
    }

    @Bean
    StubAdapter insightIdrAdapter() {
      return new StubAdapter(INSIGHT_IDR);
    }
  }

  /**
   * Documented fallback (WP-04 plan): bean-name override did NOT evict the scanned {@code
   * InsightConnectAdapter} — under Spring Boot 4, the {@code @Import}-ed stub @Bean and the
   * scanned @Component collide during configuration-class post-processing and Boot rejects the
   * redefinition with a {@code BeanDefinitionOverrideException} (the {@code
   * spring.main.allow-bean-definition- overriding} flag set via @DynamicPropertySource is not
   * honored at that registration point). So we exclude the only real {@link
   * IntegrationAdapter} @Component from component scanning via a {@link TypeExcludeFilter}, leaving
   * the two stubs as the sole adapters. This is the least-invasive mechanism that touches no main
   * source: a {@code TypeExcludeFilter} is consulted by {@code @SpringBootApplication}'s scan, so
   * the real adapter is never registered and no collision can occur.
   */
  static final class ExcludeRealAdapters
      extends org.springframework.boot.context.TypeExcludeFilter {
    @Override
    public boolean match(
        org.springframework.core.type.classreading.MetadataReader metadataReader,
        org.springframework.core.type.classreading.MetadataReaderFactory metadataReaderFactory) {
      return InsightConnectAdapter.class
          .getName()
          .equals(metadataReader.getClassMetadata().getClassName());
    }

    // TypeExcludeFilter is held in a HashSet during context-cache key derivation and asserts that
    // subclasses override equals/hashCode; this filter is stateless, so identity by type suffices.
    @Override
    public boolean equals(Object obj) {
      return obj != null && getClass() == obj.getClass();
    }

    @Override
    public int hashCode() {
      return getClass().hashCode();
    }
  }

  @MockitoBean protected S3Client s3Client;
  @Autowired protected IntegrationCache cache;
  @Autowired protected StringRedisTemplate redis;
  @Autowired protected StubAdapter insightConnectAdapter;
  @Autowired protected StubAdapter insightIdrAdapter;
  @LocalServerPort protected int port;

  @BeforeAll
  static void seedBundleOnDisk() throws IOException {
    cacheDir = Files.createTempDirectory("wp04-read-path-");
    // Bundle version must be valid semver (VendorMappingProperties enforces ^v\d+\.\d+\.\d+$),
    // and the loader derives the cache filename as vendor-mapping-<version>.tgz. The test bundle
    // is distinguished by its YAML *content* (multi-service-test.yaml), not by a version suffix.
    Path cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0.tgz");
    byte[] yaml;
    try (InputStream in =
        ReadPathTestSupport.class.getResourceAsStream(
            "/vendor-mapping/bundle/multi-service-test.yaml")) {
      assertThat(in).as("multi-service-test.yaml present on classpath").isNotNull();
      yaml = in.readAllBytes();
    }
    Files.write(cacheFile, BundleArchiveBuilder.tgzOf(yaml, "vendor-mapping.yaml"));
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
    registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
    registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "test/mappings/");
    registry.add("integration-registry.vendor-mapping.cache-dir", () -> cacheDir.toString());
    // InsightConnectClientConfig still boots even with the adapter replaced; these have no
    // defaults and fail-fast at binding, so they must be supplied.
    registry.add("integration-registry.insightconnect.base-url", () -> "http://icon.test.local");
    registry.add("integration-registry.insightconnect.icon-base", () -> "http://icon.test.local");
  }

  @BeforeEach
  void resetStateBeforeEachScenario() {
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    insightConnectAdapter.reset();
    insightIdrAdapter.reset();
  }

  // ----- shared helpers -----

  protected RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  protected RestClient.RequestHeadersSpec<?> get(String path) {
    return client().get().uri(path).header(ORG_ID_HEADER, ORG).header(USER_ID_HEADER, USER);
  }

  /** A NormalizedIntegration whose triplet resolves against the test bundle. */
  protected static NormalizedIntegration integration(
      String product, String sourceType, String sourceValue, IntegrationStatus status) {
    return new NormalizedIntegration(
        "i-" + sourceValue,
        new SourceIdentifier(sourceType, sourceValue),
        product,
        "SIEM Event Source",
        "label-" + sourceValue,
        status,
        Instant.parse("2026-06-01T00:00:00Z"),
        "https://example/config/" + sourceValue,
        ORG);
  }

  protected static FetchResult fetchResult(Instant fetchedAt, NormalizedIntegration... items) {
    return new FetchResult(List.of(items), fetchedAt);
  }

  /** Seed the FRESH tier (write-on-success writes both tiers). */
  protected void seedFresh(String product, FetchResult result) {
    cache.writeOnSuccess(ORG, product, result);
  }

  /** Seed ONLY the stale tier: write both, then delete the fresh key (mirrors expiry). */
  protected void seedStaleOnly(String product, FetchResult result) {
    cache.writeOnSuccess(ORG, product, result);
    redis.delete(CacheKey.of(CacheTier.FRESH, ORG, product));
  }
}
