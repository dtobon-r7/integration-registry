# WP-04 Full-context read-path integration suite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `@SpringBootTest` integration suite that boots the real read path (controller → service → coordinator → aggregator → cache → real Valkey) with adapters faked, and proves the six read-path scenarios from the track exit criteria end-to-end over HTTP.

**Architecture:** Full Spring context against a Testcontainers Valkey (extends the existing `ValkeyTestContainer`, ADR-006). The two `IntegrationAdapter`s are hand-written stub beans with hard-coded `productName()` (not Mockito — the coordinator validates product names in its constructor at boot). A purpose-built multi-service vendor-mapping bundle is staged on disk; `S3Client` is mocked. Each test flushes Valkey, seeds the tiers it needs via the real `IntegrationCache`, sets the stub adapters' behavior, calls the route with a `RestClient`, and asserts the composed outcome by deserializing into the Plan-01 response DTOs.

**Tech Stack:** Java 25, Spring Boot 4.0.6, JUnit 5, Testcontainers (`valkey/valkey:8-alpine`), AssertJ, Jackson 3 (`tools.jackson.*`), Maven.

---

## File Structure

- Create: `src/test/java/com/rapid7/integrationregistry/integration/StubAdapter.java` — hand-written `IntegrationAdapter` double with hard-coded `productName()` and a per-test-settable behavior (returns data or throws an `AdapterException`).
- Create: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathTestSupport.java` — abstract base: extends `ValkeyTestContainer`, declares the `@TestConfiguration` stub-adapter beans, the `@MockitoBean S3Client`, the `@BeforeAll` bundle seed, the `@DynamicPropertySource` block, the `@BeforeEach` cache flush + stub reset, and shared helpers (`RestClient` builder, `NormalizedIntegration`/`FetchResult` fixtures, cache-seeding helpers).
- Create: `src/test/resources/vendor-mapping/bundle/multi-service-test.yaml` — the multi-service test bundle.
- Create: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java` — the six scenario tests.

The base class keeps each scenario test small and avoids duplicating the heavy boot wiring. `StubAdapter` is its own file so both the base and any future suite can reuse it.

---

## Task 1: Test bundle resource

**Files:**
- Create: `src/test/resources/vendor-mapping/bundle/multi-service-test.yaml`

- [ ] **Step 1: Write the bundle**

The aggregator resolves `(productName, sourceType, sourceValue)` triplets against this
bundle. Microsoft carries two vendor services (drives the vendor-scoped scenario);
Atlassian/Jira is the single-service control. All categories (`edr`, `siem`, `itsm`)
and source types (`product_type`, `plugin_name`) are valid enum wire forms
(`VendorCategory`, `SourceType`).

Create `src/test/resources/vendor-mapping/bundle/multi-service-test.yaml`:

```yaml
# Test-only vendor-mapping bundle for the WP-04 full-context suite. Microsoft has TWO
# vendor services so the vendor-scoped scenario can assert multi-service rollup; Jira is
# the single-service control. mapping_version is test-distinct so metadata assertions are
# unambiguous.
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata:
  mapping_version: v1.0.0-test
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
              source_value: microsoft-defender-endpoint
              display_name: Microsoft Defender for Endpoint
            - product: InsightConnect
              source_type: plugin_name
              source_value: microsoft-defender
              display_name: Microsoft Defender
        - id: microsoft-sentinel
          name: Microsoft Sentinel
          category: siem
          data_sources:
            - product: InsightIDR
              source_type: product_type
              source_value: microsoft-sentinel
              display_name: Microsoft Sentinel
            - product: InsightConnect
              source_type: plugin_name
              source_value: microsoft-sentinel
              display_name: Microsoft Sentinel
    - id: atlassian
      name: Atlassian
      services:
        - id: jira
          name: Jira
          category: itsm
          data_sources:
            - product: InsightConnect
              source_type: plugin_name
              source_value: jira
              display_name: Jira
```

- [ ] **Step 2: Validate it parses against the bundle schema**

Run: `./mvnw -q -Dtest=BundleParserTest test`
Expected: PASS (existing parser tests still green — this step only confirms the build
sees the new resource without breaking; the bundle itself is exercised at boot in Task 2).

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/vendor-mapping/bundle/multi-service-test.yaml
git commit -m "test(wp-04): add multi-service vendor-mapping test bundle"
```

---

## Task 2: StubAdapter double

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/integration/StubAdapter.java`

- [ ] **Step 1: Write the stub adapter**

This is NOT a Mockito mock. `FanOutCoordinator` validates `productName()` in its
constructor at context startup (`FanOutCoordinator.java:68,81-92`); a mock's
`productName()` is null then and would fail boot. A hard-coded `productName()` survives
boot, while `fetch()` delegates to a per-test-settable behavior. Mirrors the existing
`CoordinatorAdapterFixtures.CountingAdapter` shape.

Create `src/test/java/com/rapid7/integrationregistry/integration/StubAdapter.java`:

```java
package com.rapid7.integrationregistry.integration;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * Hand-written {@link IntegrationAdapter} double for the WP-04 full-context suite. The {@code
 * productName} is hard-coded (set at construction) so it survives {@code
 * FanOutCoordinator}'s constructor-time product-name validation at boot — a Mockito mock's
 * {@code productName()} would be null there and fail boot. {@code fetch} delegates to a
 * per-test-settable {@link Behavior}; tests call {@link #willReturn}/{@link #willThrow}/{@link
 * #reset} between scenarios. Tracks an invocation count so a test can assert a fresh cache hit
 * skipped the adapter call.
 */
final class StubAdapter implements IntegrationAdapter {

  /** A fetch behavior that may throw any of the declared adapter exceptions. */
  @FunctionalInterface
  interface Behavior {
    FetchResult apply(String orgId, HttpHeaders headers)
        throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException;
  }

  private final String productName;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile Behavior behavior;

  StubAdapter(String productName) {
    this.productName = productName;
    this.behavior = defaultBehavior();
  }

  private static Behavior defaultBehavior() {
    return (orgId, headers) -> {
      throw new IllegalStateException(
          "StubAdapter behavior not configured for this scenario; call willReturn/willThrow");
    };
  }

  /** Configure this adapter to return {@code result} on the next fetch(es). */
  void willReturn(FetchResult result) {
    this.behavior = (orgId, headers) -> result;
  }

  /** Configure this adapter to throw {@code toThrow} on the next fetch(es). */
  void willThrow(AdapterException toThrow) {
    this.behavior =
        (orgId, headers) -> {
          switch (toThrow) {
            case AdapterTimeoutException e -> throw e;
            case AdapterAuthException e -> throw e;
            case AdapterUpstreamException e -> throw e;
            default -> throw new IllegalStateException("unexpected adapter exception type");
          }
        };
  }

  /** Reset call count and behavior between scenarios. */
  void reset() {
    this.calls.set(0);
    this.behavior = defaultBehavior();
  }

  int callCount() {
    return calls.get();
  }

  @Override
  public String productName() {
    return productName;
  }

  @Override
  public FetchResult fetch(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    calls.incrementAndGet();
    return behavior.apply(orgId, authHeaders);
  }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q test-compile`
Expected: BUILD SUCCESS (the class compiles; it has no test yet — it is exercised by Task 3).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/StubAdapter.java
git commit -m "test(wp-04): add StubAdapter double for full-context suite"
```

---

## Task 3: Walking skeleton — boot the context with exactly two stub adapters

This is the de-risking task. It proves the full context boots against real Valkey with
the two stub adapters in the coordinator's set and the real `InsightConnectAdapter`
evicted, BEFORE any scenario logic is layered on. If bean-name override does not evict
the real adapter, this task fails loudly and in isolation.

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathTestSupport.java`
- Create: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the base support class**

The ICON stub `@Bean` method is named `insightConnectAdapter` to collide with and
replace the scanned `InsightConnectAdapter @Component`; `spring.main.allow-bean-definition-overriding=true`
makes that deterministic. `InsightConnectClientConfig` still boots, so `insightconnect.base-url`
and `.icon-base` remain required. The bundle quartet + `S3Client` mock follow
`IntegrationRegistryApplicationTests`.

Create `src/test/java/com/rapid7/integrationregistry/integration/ReadPathTestSupport.java`:

```java
package com.rapid7.integrationregistry.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.FetchResult;
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
 * (real controller/service/coordinator/aggregator/cache) against a Testcontainers Valkey
 * (ADR-006, via {@link com.rapid7.integrationregistry.cache.ValkeyTestContainer}), with the two
 * adapters faked by {@link StubAdapter} beans and {@code S3Client} mocked. A test-only
 * multi-service vendor-mapping bundle is staged on disk so the bundle loader resolves the seeded
 * vendor services without S3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
   * Supplies exactly the two stub adapters the coordinator autowires. The {@code
   * insightConnectAdapter} bean name collides with the scanned {@code InsightConnectAdapter}
   * @Component and replaces it (overriding enabled below); {@code insightIdrAdapter} adds the
   * second product. Both have hard-coded product names, so the coordinator's constructor-time
   * validation passes at boot.
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

  @MockitoBean protected S3Client s3Client;
  @Autowired protected IntegrationCache cache;
  @Autowired protected StringRedisTemplate redis;
  @Autowired protected StubAdapter insightConnectAdapter;
  @Autowired protected StubAdapter insightIdrAdapter;
  @LocalServerPort protected int port;

  @BeforeAll
  static void seedBundleOnDisk() throws IOException {
    cacheDir = Files.createTempDirectory("wp04-read-path-");
    Path cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0-test.tgz");
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
    // Deterministic bean-name override so the InsightConnect stub replaces the real @Component.
    registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
    registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0-test");
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
```

- [ ] **Step 2: Write the failing skeleton test**

Create `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`:

```java
package com.rapid7.integrationregistry.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Full-context read-path integration suite (WP-04). Boots the real read path against a
 * Testcontainers Valkey with adapters faked, and proves the six track exit-criteria scenarios
 * end-to-end over HTTP.
 */
class ReadPathIntegrationTest extends ReadPathTestSupport {

  @Autowired private FanOutCoordinator coordinator;

  @Test
  void contextBoots_withExactlyTwoStubAdapters() {
    // Arrange — context booted via @SpringBootTest; nothing per-test needed.

    // Act — both stub adapters are injected and the coordinator wired.
    // Assert — the two stubs are present with the expected product names; the real
    // InsightConnectAdapter was evicted by the name-colliding stub bean.
    assertThat(insightConnectAdapter.productName()).isEqualTo(INSIGHT_CONNECT);
    assertThat(insightIdrAdapter.productName()).isEqualTo(INSIGHT_IDR);
    assertThat(coordinator).isNotNull();
  }
}
```

- [ ] **Step 3: Run it — expect either a clean PASS or a precise boot failure**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest test` (Docker daemon must be running)
Expected: PASS. If it FAILS with a duplicate-product-name `IllegalStateException` from
`FanOutCoordinator`, the real adapter was NOT evicted — switch the eviction mechanism to a
component-scan exclude filter on `InsightConnectAdapter` (the documented fallback in the
spec) and re-run. Do not proceed to Task 4 until this passes.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathTestSupport.java \
        src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): boot full read-path context with two stub adapters (skeleton)"
```

---

## Task 4: Scenario 1 — cache-hit happy path

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Pre-seed BOTH products' fresh tier; the coordinator must short-circuit the adapter call
(`FanOutCoordinator.fetchOne` returns on a fresh hit), so `cache_hit` is true and neither
stub's `fetch()` runs.

Add these imports to `ReadPathIntegrationTest.java`:

```java
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import java.time.Instant;
```

Add the test method inside the class:

```java
  @Test
  void vendorServices_cacheHit_returnsCacheHitTrue_andSkipsAdapters() {
    // Arrange — both products pre-seeded fresh; adapters left unconfigured (must not be called).
    Instant idrFetchedAt = Instant.parse("2026-06-01T09:00:00Z");
    Instant iconFetchedAt = Instant.parse("2026-06-01T10:00:00Z");
    seedFresh(
        INSIGHT_IDR,
        fetchResult(
            idrFetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    seedFresh(
        INSIGHT_CONNECT,
        fetchResult(
            iconFetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — fresh on every product => cache_hit true; as_of is the OLDEST contributing fetch;
    // adapters never called; mapping_version from the test bundle.
    assertThat(body.metadata().cacheHit()).isTrue();
    assertThat(body.metadata().asOf()).isEqualTo(idrFetchedAt);
    assertThat(body.metadata().mappingVersion()).isEqualTo("v1.0.0-test");
    assertThat(body.unavailableProducts()).isEmpty();
    assertThat(body.vendorServices()).isNotEmpty();
    assertThat(insightIdrAdapter.callCount()).isZero();
    assertThat(insightConnectAdapter.callCount()).isZero();
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorServices_cacheHit_returnsCacheHitTrue_andSkipsAdapters test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 1 — cache-hit happy path"
```

---

## Task 5: Scenario 2 — cache-miss, both products fetched

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Empty cache; both adapters return fresh data. The coordinator calls each `fetch()`,
caches the success, and `cache_hit` is false (a fetched product is not a fresh-tier hit).

```java
  @Test
  void vendorServices_cacheMiss_fetchesBoth_andReportsCacheHitFalse() {
    // Arrange — empty cache (flushed in @BeforeEach); both adapters return data.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — both fetched => cache_hit false; both adapters invoked; payload present.
    assertThat(body.metadata().cacheHit()).isFalse();
    assertThat(body.unavailableProducts()).isEmpty();
    assertThat(body.vendorServices()).isNotEmpty();
    assertThat(insightIdrAdapter.callCount()).isEqualTo(1);
    assertThat(insightConnectAdapter.callCount()).isEqualTo(1);
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorServices_cacheMiss_fetchesBoth_andReportsCacheHitFalse test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 2 — cache-miss fetches both products"
```

---

## Task 6: Scenario 3 — partial unavailability with stale fallback

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

IDR has stale-only data and throws a transient `AdapterUpstreamException`; the coordinator
serves stale (`Served` with `stale:true`). ICON returns fresh. IDR appears in
`unavailable_products[]` with `stale:true` + `stale_since`; `as_of` is the oldest
contributing `fetched_at` (the stale IDR fetch).

Add import:

```java
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import com.rapid7.integrationregistry.controller.dto.UnavailableProductDto;
```

```java
  @Test
  void vendorServices_partialWithStale_servesStale_andReportsStaleUnavailable() {
    // Arrange — IDR: stale-only + transient failure => coordinator serves stale.
    Instant staleFetchedAt = Instant.parse("2026-05-30T06:00:00Z");
    Instant iconFetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    seedStaleOnly(
        INSIGHT_IDR,
        fetchResult(
            staleFetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    insightIdrAdapter.willThrow(new AdapterUpstreamException("503 from IDR"));
    insightConnectAdapter.willReturn(
        fetchResult(
            iconFetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — IDR served stale: in unavailable_products with stale:true + stale_since; as_of is
    // the oldest contributing fetch (the stale IDR fetch); the grid still carries IDR's data.
    assertThat(body.metadata().cacheHit()).isFalse();
    assertThat(body.metadata().asOf()).isEqualTo(staleFetchedAt);
    UnavailableProductDto idr =
        body.unavailableProducts().stream()
            .filter(u -> u.productName().equals(INSIGHT_IDR))
            .findFirst()
            .orElseThrow();
    assertThat(idr.stale()).isTrue();
    assertThat(idr.staleSince()).isEqualTo(staleFetchedAt);
    assertThat(body.vendorServices()).isNotEmpty();
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorServices_partialWithStale_servesStale_andReportsStaleUnavailable test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 3 — partial unavailability with stale fallback"
```

---

## Task 7: Scenario 4 — partial unavailability with omission

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

IDR throws a permanent `AdapterAuthException` and has no stale; the coordinator omits it
(`Unavailable`, never reads stale). ICON returns data. IDR appears in
`unavailable_products[]` with `stale:false` + `reason: auth_failure`.

Add imports:

```java
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.controller.dto.UnavailableReason;
```

```java
  @Test
  void vendorServices_partialWithOmission_omitsProduct_andReportsReason() {
    // Arrange — IDR: no stale + permanent auth failure => omitted entirely; ICON returns data.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willThrow(new AdapterAuthException("401 from IDR"));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — IDR omitted: stale:false + reason auth_failure, no stale_since; ICON present.
    UnavailableProductDto idr =
        body.unavailableProducts().stream()
            .filter(u -> u.productName().equals(INSIGHT_IDR))
            .findFirst()
            .orElseThrow();
    assertThat(idr.stale()).isFalse();
    assertThat(idr.reason()).isEqualTo(UnavailableReason.AUTH_FAILURE);
    assertThat(idr.staleSince()).isNull();
    assertThat(body.vendorServices()).isNotEmpty();
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorServices_partialWithOmission_omitsProduct_andReportsReason test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 4 — partial unavailability with omission"
```

---

## Task 8: Scenario 5 — all-adapter-failure shape

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Both adapters throw, no stale. The response is 200 with an empty top-level array and one
`unavailable_products[]` entry per failed product. Use `.toEntity(...)` to assert the 200
status explicitly.

Add import:

```java
import org.springframework.http.ResponseEntity;
```

```java
  @Test
  void vendorServices_allAdaptersFail_returns200_withEmptyArray_andOneEntryPerProduct() {
    // Arrange — both throw, no stale anywhere.
    insightIdrAdapter.willThrow(new AdapterUpstreamException("503 from IDR"));
    insightConnectAdapter.willThrow(new AdapterUpstreamException("503 from ICON"));

    // Act
    ResponseEntity<VendorServicesResponse> response =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .toEntity(VendorServicesResponse.class);

    // Assert — 200 (partial/total unavailability is never a 5xx); empty grid; one entry per
    // failed product, distinguishing "couldn't reach anything" from "org has no integrations".
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    VendorServicesResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.vendorServices()).isEmpty();
    assertThat(body.unavailableProducts())
        .extracting(UnavailableProductDto::productName)
        .containsExactlyInAnyOrder(INSIGHT_IDR, INSIGHT_CONNECT);
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorServices_allAdaptersFail_returns200_withEmptyArray_andOneEntryPerProduct test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 5 — all-adapter-failure shape"
```

---

## Task 9: Scenario 6 — vendor-scoped projection with multiple vendor services

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Both adapters return data spanning BOTH Microsoft services (defender + sentinel) across
both products. `GET /vendors/microsoft` returns the nested vendor-service projection with
two services and a rolled-up vendor-level `aggregate_health` + `last_updated`.

Add imports:

```java
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServiceCardNestedDto;
```

```java
  @Test
  void vendorDetail_multiService_returnsNestedProjection_withRollups() {
    // Arrange — IDR contributes to both MS services; ICON also contributes to both. A WARNING on
    // one instance makes the vendor-level rollup observably non-trivial.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY),
            integration(
                INSIGHT_IDR, "product_type", "microsoft-sentinel", IntegrationStatus.WARNING)));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_CONNECT, "plugin_name", "microsoft-defender", IntegrationStatus.HEALTHY),
            integration(
                INSIGHT_CONNECT, "plugin_name", "microsoft-sentinel", IntegrationStatus.HEALTHY)));

    // Act
    VendorDetailResponse body =
        get("/integration-registry/v1/vendors/microsoft")
            .retrieve()
            .body(VendorDetailResponse.class);

    // Assert — vendor header present; two nested vendor services; vendor-level rollups populated.
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.vendorName()).isEqualTo("Microsoft");
    assertThat(body.vendorServices())
        .extracting(VendorServiceCardNestedDto::vendorServiceId)
        .containsExactlyInAnyOrder("microsoft-defender", "microsoft-sentinel");
    assertThat(body.aggregateHealth()).isNotNull();
    assertThat(body.lastUpdated()).isNotNull();
    assertThat(body.unavailableProducts()).isEmpty();
  }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest#vendorDetail_multiService_returnsNestedProjection_withRollups test`
Expected: PASS. (If the nested DTO accessor is not `vendorServiceId()`, open
`VendorServiceCardNestedDto.java` and use its actual accessor — adjust the extractor.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java
git commit -m "test(wp-04): scenario 6 — vendor-scoped multi-service projection"
```

---

## Task 10: Full suite green + quality gates

**Files:** none (verification only).

- [ ] **Step 1: Run the whole new suite**

Run: `./mvnw -q -Dtest=ReadPathIntegrationTest test`
Expected: all 7 tests PASS (skeleton + six scenarios).

- [ ] **Step 2: Run the full build with all gates**

Run: `./mvnw -q verify` (Docker daemon must be running for the Valkey tests)
Expected: BUILD SUCCESS — Spotless, PMD (applies to test sources), ArchUnit, and the
entire test suite pass.

- [ ] **Step 3: If Spotless fails, auto-format and re-run**

Run: `./mvnw spotless:apply && ./mvnw -q verify`
Expected: BUILD SUCCESS. Do not hand-format to match GJF — the formatter is authoritative.

- [ ] **Step 4: Commit any formatting fixup**

```bash
git add -A
git commit -m "style(wp-04): spotless formatting" || echo "nothing to format"
```

---

## Self-Review notes (author)

- **Spec coverage:** Tasks 4–9 implement the six scenarios from the spec's scenario
  matrix one-to-one; Task 3 is the de-risking walking skeleton; Task 1 is the
  multi-service bundle; Task 2 is the stub double. All six acceptance signals are covered.
- **Verified signatures:** `VendorServicesResponse(vendorServices, unavailableProducts,
  metadata)`, `VendorDetailResponse(vendorId, vendorName, aggregateHealth, lastUpdated,
  vendorServices, unavailableProducts, metadata)`, `UnavailableProductDto` accessors
  (`productName`, `stale`, `reason`, `staleSince`), `ResponseMetadataDto`
  (`cacheHit`, `asOf`, `mappingVersion`), `IntegrationCache.writeOnSuccess`,
  `CacheKey.of(CacheTier, org, product)`, `AdapterUpstreamException`/`AdapterAuthException`
  constructors, `SourceIdentifier(sourceType, sourceValue)`, enum wire forms — all read
  from source.
- **Open risk flagged in-task:** bean-name override eviction (Task 3 Step 3) has an
  explicit fallback. `VendorServiceCardNestedDto` accessor name is verified at Task 9
  Step 2 with a fallback note.
- **Cache-hit `as_of`:** asserted as the oldest contributing fetch per `VendorService.metadata`.
