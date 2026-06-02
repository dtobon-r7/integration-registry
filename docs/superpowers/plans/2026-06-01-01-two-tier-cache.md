# Two-tier Valkey Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained two-tier (fresh/stale) cache component for the Integration Registry, backed by Valkey, with independently configurable TTLs, a single-call-site key function, a write-on-success-only / never-overwrite-good-stale invariant, and reads that degrade to a miss on any Valkey or decode failure.

**Architecture:** A new top-level `com.rapid7.integrationregistry.cache` layer the coordinator depends on. `IntegrationCache` wraps a Spring Data Redis `StringRedisTemplate` (Lettuce). The two tiers are key-prefixed namespaces (`ir:cache:fresh:…` / `ir:cache:stale:…`) with independent per-key TTLs. `CacheKey` is the only key-construction site. `FetchResultCodec` serializes `FetchResult` to a versioned JSON envelope; unreadable/old-version payloads decode to empty (a miss). Connection comes from standard `spring.data.redis.*` (auto-configured `StringRedisTemplate`); TTLs come from `integration-registry.cache.*` via `CacheProperties`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Data Redis + Lettuce (Valkey-compatible), Jackson (JSON + JavaTime), Testcontainers `valkey/valkey:8-alpine` (per ADR-006), JUnit 5, AssertJ, ArchUnit, PMD, Spotless.

**Decisions grounding this plan:** ADR-005 (Valkey backing store), ADR-006 (Testcontainers for cache tests, amending TESTING.md), and the approved spec `docs/superpowers/specs/2026-06-01-01-two-tier-cache-design.md`.

**Refinement from spec (flagged):** the spec sketched a custom `ValkeyCacheConfig` (`LettuceConnectionFactory`) and `integration-registry.cache.valkey.*` connection keys. This plan instead uses Boot's idiomatic `spring.data.redis.*` auto-configuration (auto-provides a `StringRedisTemplate`), keeping `CacheProperties` for the two TTLs only. Same capability, less custom wiring. No separate `ValkeyCacheConfig` bean is needed unless a later task proves otherwise.

---

## File structure

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | Add `spring-boot-starter-data-redis`; add Testcontainers BOM import + `testcontainers`/`junit-jupiter` (test scope). |
| `cache/package-info.java` (create) | Layer doc. |
| `cache/CacheTier.java` (create) | `enum { FRESH, STALE }` with its lowercase wire token. |
| `cache/CacheKey.java` (create) | The single Valkey-key construction site. |
| `cache/FetchResultCodec.java` (create) | `FetchResult` ↔ versioned JSON envelope; decode failure → empty. |
| `cache/CacheProperties.java` (create) | `@ConfigurationProperties("integration-registry.cache")` — `freshTtl`, `staleTtl`. |
| `cache/StaleEntry.java` (create) | `record(FetchResult result, Instant staleSince)`. |
| `cache/IntegrationCache.java` (create) | The component: `readFresh` / `readStale` / `writeOnSuccess`. |
| `src/main/resources/application.yaml` (modify) | TTL defaults + `spring.data.redis` block. |
| `architecture/LayerDependencyRules.java` (modify) | Add `cacheLayer_shouldNotDependOnDisallowedLayers`. |
| `architecture/LayerDependencyRulesTest.java` (modify) | Wire the new `@ArchTest`. |
| `test/.../cache/CacheKeyTest.java` (create) | Key format + single-site contract. |
| `test/.../cache/FetchResultCodecTest.java` (create) | Round-trip + version-mismatch = empty. |
| `test/.../cache/CachePropertiesBindingTest.java` (create) | TTL binding + defaults. |
| `test/.../cache/IntegrationCacheValkeyTest.java` (create) | Testcontainers integration: tiers, no-overwrite, expiry, error=miss. |
| `test/.../cache/ValkeyTestContainer.java` (create) | Shared Testcontainers Valkey + dynamic property registration. |
| `test/.../cache/CacheFetchResultFixtures.java` (create) | `FetchResult` builders for cache tests. |

---

## Task 1: Add dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the Redis starter to `<dependencies>`**

Insert after the existing `spring-boot-starter-actuator` dependency (around `pom.xml:28`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-redis</artifactId>
		</dependency>
```

- [ ] **Step 2: Add Testcontainers (test scope) to `<dependencies>`**

Insert after the `archunit-junit5` dependency (around `pom.xml:46`). Versions are managed by the Spring Boot 4 BOM (Testcontainers 2.0.5) — do NOT add explicit `<version>` tags:

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 3: Verify the dependency tree resolves**

Run: `./mvnw -q dependency:resolve`
Expected: BUILD SUCCESS; no "missing artifact" errors. `spring-data-redis`, `lettuce-core`, and `org.testcontainers:testcontainers:2.0.5` appear in the resolved set.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(cache): add spring-data-redis and Testcontainers for the Valkey cache"
```

---

## Task 2: `CacheTier` enum

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/cache/CacheTier.java`
- Create: `src/main/java/com/rapid7/integrationregistry/cache/package-info.java`

- [ ] **Step 1: Create the package-info**

```java
/** Two-tier Valkey-backed integration cache: fresh/stale tiers, single-call-site key, write-on-success. */
package com.rapid7.integrationregistry.cache;
```

- [ ] **Step 2: Create the enum**

```java
package com.rapid7.integrationregistry.cache;

/** The two independent cache tiers. {@code token} is the lowercase segment used in the Valkey key. */
enum CacheTier {
  FRESH("fresh"),
  STALE("stale");

  private final String token;

  CacheTier(String token) {
    this.token = token;
  }

  String token() {
    return token;
  }
}
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/
git commit -m "feat(cache): add CacheTier enum and package-info"
```

---

## Task 3: `CacheKey` — the single key-construction site

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/cache/CacheKey.java`
- Test: `src/test/java/com/rapid7/integrationregistry/cache/CacheKeyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheKeyTest {

  @Test
  void of_shouldBuildFreshKey_whenTierIsFresh() {
    // Act
    String key = CacheKey.of(CacheTier.FRESH, "org-123", "InsightConnect");

    // Assert
    assertThat(key).isEqualTo("ir:cache:fresh:org-123:InsightConnect");
  }

  @Test
  void of_shouldBuildStaleKey_whenTierIsStale() {
    // Act
    String key = CacheKey.of(CacheTier.STALE, "org-123", "InsightIDR");

    // Assert
    assertThat(key).isEqualTo("ir:cache:stale:org-123:InsightIDR");
  }

  @Test
  void of_shouldProduceDistinctKeysPerTier_whenOrgAndProductIdentical() {
    // Act
    String fresh = CacheKey.of(CacheTier.FRESH, "org-1", "InsightConnect");
    String stale = CacheKey.of(CacheTier.STALE, "org-1", "InsightConnect");

    // Assert — distinct keys are what make the two tiers independent in Valkey
    assertThat(fresh).isNotEqualTo(stale);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=CacheKeyTest`
Expected: FAIL — `CacheKey` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.rapid7.integrationregistry.cache;

/**
 * The single Valkey-key construction site for the cache. Keys are {@code
 * ir:cache:{tier}:{orgId}:{productName}}.
 *
 * <p>This is deliberately the only place a cache key is built: the two tiers stay independent
 * because their keys differ by the {@code tier} segment, and a future change to a user-scoped key
 * — {@code (orgId, userId, productName)} — touches only this method and its direct callers.
 *
 * <p>Assumption: {@code orgId} and {@code productName} are platform-controlled values that do not
 * contain the {@code ':'} delimiter ({@code productName} is the RFC-canonical frozen string set;
 * {@code orgId} is a platform org identifier). If a future key dimension can contain {@code ':'},
 * its encoding belongs here.
 */
final class CacheKey {

  private static final String PREFIX = "ir:cache:";

  private CacheKey() {}

  static String of(CacheTier tier, String orgId, String productName) {
    return PREFIX + tier.token() + ':' + orgId + ':' + productName;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=CacheKeyTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/CacheKey.java src/test/java/com/rapid7/integrationregistry/cache/CacheKeyTest.java
git commit -m "feat(cache): add single-call-site CacheKey function"
```

---

## Task 4: `StaleEntry` record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/cache/StaleEntry.java`

- [ ] **Step 1: Create the record**

```java
package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Instant;
import java.util.Objects;

/**
 * A stale-tier read result: the cached {@link FetchResult} plus the timestamp the data was
 * originally fetched from the product ({@code staleSince}). The coordinator uses {@code staleSince}
 * to populate {@code stale_since} on the downstream {@code unavailable_products} entry.
 */
public record StaleEntry(FetchResult result, Instant staleSince) {

  public StaleEntry {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(staleSince, "staleSince");
  }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/StaleEntry.java
git commit -m "feat(cache): add StaleEntry record for stale-tier reads"
```

---

## Task 5: `FetchResultCodec` — versioned JSON envelope

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/cache/FetchResultCodec.java`
- Test: `src/test/java/com/rapid7/integrationregistry/cache/FetchResultCodecTest.java`
- Test: `src/test/java/com/rapid7/integrationregistry/cache/CacheFetchResultFixtures.java`

- [ ] **Step 1: Create the shared FetchResult fixture helper**

```java
package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import java.time.Instant;
import java.util.List;

/** Builds {@link FetchResult} fixtures for cache tests — real records, no mocks. */
final class CacheFetchResultFixtures {

  private CacheFetchResultFixtures() {}

  static FetchResult iconResult(Instant fetchedAt) {
    NormalizedIntegration integration =
        new NormalizedIntegration(
            "conn-1",
            new SourceIdentifier("plugin_name", "jira"),
            "InsightConnect",
            "Automation Plugin",
            null,
            IntegrationStatus.HEALTHY,
            null,
            "https://icon.example/connections/conn-1",
            "org-123");
    return new FetchResult(List.of(integration), fetchedAt);
  }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FetchResultCodecTest {

  private final FetchResultCodec codec = new FetchResultCodec();

  @Test
  void decode_shouldReturnEquivalentResult_whenRoundTrippingEncodedValue() {
    // Arrange
    Instant fetchedAt = Instant.parse("2026-06-01T12:00:00Z");
    FetchResult original = CacheFetchResultFixtures.iconResult(fetchedAt);

    // Act
    String json = codec.encode(original);
    Optional<FetchResult> decoded = codec.decode(json);

    // Assert
    assertThat(decoded).contains(original);
  }

  @Test
  void encode_shouldEmbedSchemaVersion_whenEncoding() {
    // Act
    String json = codec.encode(CacheFetchResultFixtures.iconResult(Instant.EPOCH));

    // Assert
    assertThat(json).contains("\"v\":1");
  }

  @Test
  void decode_shouldReturnEmpty_whenVersionIsUnknown() {
    // Arrange — a future/unknown envelope version
    String json = "{\"v\":999,\"payload\":{\"integrations\":[],\"fetchedAt\":\"2026-06-01T12:00:00Z\"}}";

    // Act
    Optional<FetchResult> decoded = codec.decode(json);

    // Assert
    assertThat(decoded).isEmpty();
  }

  @Test
  void decode_shouldReturnEmpty_whenPayloadIsMalformed() {
    // Act
    Optional<FetchResult> decoded = codec.decode("not json at all");

    // Assert — never throws; a corrupt value is just a miss
    assertThat(decoded).isEmpty();
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=FetchResultCodecTest`
Expected: FAIL — `FetchResultCodec` does not exist.

- [ ] **Step 4: Write minimal implementation**

```java
package com.rapid7.integrationregistry.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rapid7.integrationregistry.adapter.FetchResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes a {@link FetchResult} to a versioned JSON envelope for Valkey storage and back.
 *
 * <p>The envelope is {@code {"v":1,"payload":{...}}}. On read, an unknown version or any
 * deserialization failure yields {@link Optional#empty()} — a cache miss, never an escaping
 * exception. This is what makes a future {@code NormalizedIntegration} schema change safe: old or
 * incompatible entries are silently ignored rather than breaking the read path.
 */
class FetchResultCodec {

  private static final Logger log = LoggerFactory.getLogger(FetchResultCodec.class);
  private static final int CURRENT_VERSION = 1;

  private final ObjectMapper mapper =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** JSON envelope wrapping a payload with its schema version. */
  private record Envelope(@JsonProperty("v") int v, @JsonProperty("payload") FetchResult payload) {
    @JsonCreator
    Envelope {}
  }

  String encode(FetchResult result) {
    try {
      return mapper.writeValueAsString(new Envelope(CURRENT_VERSION, result));
    } catch (JsonProcessingException e) {
      // FetchResult is a plain record of serializable fields; this should not happen.
      throw new IllegalStateException("Failed to encode FetchResult for cache", e);
    }
  }

  Optional<FetchResult> decode(String json) {
    if (json == null) {
      return Optional.empty();
    }
    try {
      Envelope envelope = mapper.readValue(json, Envelope.class);
      if (envelope.v() != CURRENT_VERSION || envelope.payload() == null) {
        log.debug("Cache payload version {} not readable; treating as miss", envelope.v());
        return Optional.empty();
      }
      return Optional.of(envelope.payload());
    } catch (JsonProcessingException e) {
      log.debug("Cache payload unreadable; treating as miss", e);
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FetchResultCodecTest`
Expected: PASS (4 tests).

Note: if the round-trip test fails on `Instant` equality, confirm `jackson-datatype-jsr310` is on the classpath — it is transitively provided by `spring-boot-starter-json` (pulled in by `spring-boot-starter-web`). The `JavaTimeModule` import resolves from there.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/FetchResultCodec.java src/test/java/com/rapid7/integrationregistry/cache/FetchResultCodecTest.java src/test/java/com/rapid7/integrationregistry/cache/CacheFetchResultFixtures.java
git commit -m "feat(cache): add versioned-envelope FetchResultCodec"
```

---

## Task 6: `CacheProperties` — configurable TTLs

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/cache/CacheProperties.java`
- Test: `src/test/java/com/rapid7/integrationregistry/cache/CachePropertiesBindingTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CachePropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void binding_shouldApplyConfiguredTtls_whenPropertiesPresent() {
    runner
        .withPropertyValues(
            "integration-registry.cache.fresh-ttl=10m",
            "integration-registry.cache.stale-ttl=48h")
        .run(
            context -> {
              CacheProperties props = context.getBean(CacheProperties.class);
              // Assert
              assertThat(props.freshTtl()).isEqualTo(Duration.ofMinutes(10));
              assertThat(props.staleTtl()).isEqualTo(Duration.ofHours(48));
            });
  }

  @Test
  void binding_shouldApplyRfcDefaults_whenPropertiesAbsent() {
    runner.run(
        context -> {
          CacheProperties props = context.getBean(CacheProperties.class);
          // Assert — RFC starting points
          assertThat(props.freshTtl()).isEqualTo(Duration.ofMinutes(5));
          assertThat(props.staleTtl()).isEqualTo(Duration.ofHours(24));
        });
  }

  @Configuration
  @EnableConfigurationProperties(CacheProperties.class)
  static class TestConfig {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=CachePropertiesBindingTest`
Expected: FAIL — `CacheProperties` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.rapid7.integrationregistry.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTL configuration for the two cache tiers, bound from {@code integration-registry.cache.*}.
 *
 * <p>Defaults are the RFC-001 §Cache layer starting points (fresh 5 min, stale 24 h) and are
 * independently overridable per environment. Connection settings come from the standard {@code
 * spring.data.redis.*} tree (Boot auto-configuration), not this record.
 */
@ConfigurationProperties("integration-registry.cache")
public record CacheProperties(Duration freshTtl, Duration staleTtl) {

  private static final Duration DEFAULT_FRESH_TTL = Duration.ofMinutes(5);
  private static final Duration DEFAULT_STALE_TTL = Duration.ofHours(24);

  public CacheProperties {
    if (freshTtl == null) {
      freshTtl = DEFAULT_FRESH_TTL;
    }
    if (staleTtl == null) {
      staleTtl = DEFAULT_STALE_TTL;
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=CachePropertiesBindingTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/CacheProperties.java src/test/java/com/rapid7/integrationregistry/cache/CachePropertiesBindingTest.java
git commit -m "feat(cache): add CacheProperties with RFC-default TTLs"
```

---

## Task 7: `IntegrationCache` + Testcontainers integration tests

This is the component itself. Its behavior is verified against a real Valkey via Testcontainers (ADR-006).

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/cache/ValkeyTestContainer.java`
- Create: `src/main/java/com/rapid7/integrationregistry/cache/IntegrationCache.java`
- Test: `src/test/java/com/rapid7/integrationregistry/cache/IntegrationCacheValkeyTest.java`

- [ ] **Step 1: Create the shared Testcontainers Valkey base**

```java
package com.rapid7.integrationregistry.cache;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers Valkey (ADR-006) for cache integration tests. Uses {@code
 * valkey/valkey:8-alpine} — the same image the live stack runs — and points {@code
 * spring.data.redis.*} at it via {@link DynamicPropertySource}.
 *
 * <p>Requires a running Docker daemon. This is the documented exception to the otherwise
 * Docker-free test suite (see TESTING.md).
 */
@Testcontainers
abstract class ValkeyTestContainer {

  @Container
  static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", VALKEY::getHost);
    registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
  }
}
```

- [ ] **Step 2: Write the failing integration test**

```java
package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.DataRedisTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Integration tests for {@link IntegrationCache} against a real Valkey (Testcontainers, ADR-006).
 *
 * <p>Uses the {@code @DataRedisTest} slice rather than a full {@code @SpringBootTest}: this
 * auto-configures only the Redis/Lettuce {@code StringRedisTemplate} against the Testcontainers
 * Valkey and pulls in just {@link IntegrationCache} + {@link CacheProperties}. The slice
 * deliberately does NOT boot the vendor-mapping S3 loader or the InsightConnect adapter, so this
 * test needs no S3 mock, no seeded bundle, and no ICON base-url properties — the cache is tested in
 * isolation, which is the whole point of the slice.
 */
@DataRedisTest
@Import(IntegrationCache.class)
@EnableConfigurationProperties(CacheProperties.class)
class IntegrationCacheValkeyTest extends ValkeyTestContainer {

  @Autowired IntegrationCache cache;
  @Autowired StringRedisTemplate redis;

  private static final String ORG = "org-itest";
  private static final String PRODUCT = "InsightConnect";

  @Test
  void writeOnSuccess_shouldPopulateBothTiers_whenWritten() {
    // Arrange
    FetchResult result = CacheFetchResultFixtures.iconResult(Instant.parse("2026-06-01T12:00:00Z"));

    // Act
    cache.writeOnSuccess(ORG, PRODUCT, result);

    // Assert
    assertThat(cache.readFresh(ORG, PRODUCT)).contains(result);
    Optional<StaleEntry> stale = cache.readStale(ORG, PRODUCT);
    assertThat(stale).isPresent();
    assertThat(stale.get().result()).isEqualTo(result);
    assertThat(stale.get().staleSince()).isEqualTo(result.fetchedAt());
  }

  @Test
  void readFresh_shouldReturnEmpty_whenKeyAbsent() {
    // Act / Assert
    assertThat(cache.readFresh("org-absent", PRODUCT)).isEmpty();
  }

  @Test
  void readStale_shouldStillReturnEntry_whenFreshKeyManuallyEvicted() {
    // Arrange
    FetchResult result = CacheFetchResultFixtures.iconResult(Instant.parse("2026-06-01T12:00:00Z"));
    cache.writeOnSuccess(ORG, PRODUCT, result);

    // Act — simulate fresh-tier expiry by deleting only the fresh key; stale must survive
    redis.delete("ir:cache:fresh:" + ORG + ":" + PRODUCT);

    // Assert — tiers are independent: fresh is a miss, stale is still served
    assertThat(cache.readFresh(ORG, PRODUCT)).isEmpty();
    assertThat(cache.readStale(ORG, PRODUCT)).isPresent();
  }

  @Test
  void readStale_shouldBeUnchanged_whenNoSuccessfulFetchOccurs() {
    // Arrange — a good stale entry exists
    FetchResult good = CacheFetchResultFixtures.iconResult(Instant.parse("2026-06-01T12:00:00Z"));
    cache.writeOnSuccess(ORG, PRODUCT, good);
    StaleEntry before = cache.readStale(ORG, PRODUCT).orElseThrow();

    // Act — no writeOnSuccess call (this is what a failed fetch does: nothing)

    // Assert — the good stale entry is observably identical
    StaleEntry after = cache.readStale(ORG, PRODUCT).orElseThrow();
    assertThat(after.result()).isEqualTo(before.result());
    assertThat(after.staleSince()).isEqualTo(before.staleSince());
  }

  @Test
  void readFresh_shouldReturnEmpty_whenStoredPayloadIsCorrupt() {
    // Arrange — a corrupt value written directly under the fresh key
    redis.opsForValue().set("ir:cache:fresh:" + ORG + ":corrupt", "not a valid envelope");

    // Act / Assert — decode failure is a miss, never an exception
    assertThat(cache.readFresh(ORG, "corrupt")).isEmpty();
  }

  @Test
  void readFresh_shouldExpire_whenFreshTtlElapses() {
    // This test uses a short fresh-ttl set via the @SpringBootTest property below.
    FetchResult result = CacheFetchResultFixtures.iconResult(Instant.now());
    cache.writeOnSuccess(ORG, "ExpiryProbe", result);

    // Assert — present now, gone after the (short) fresh TTL
    assertThat(cache.readFresh(ORG, "ExpiryProbe")).isPresent();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(cache.readFresh(ORG, "ExpiryProbe")).isEmpty());
  }
}
```

Note on the expiry test: set a short fresh TTL for this test class by adding
`@SpringBootTest(properties = "integration-registry.cache.fresh-ttl=1s")` if the default 5 min
makes the expiry test too slow — but keep the stale TTL at its default so the other tests'
stale reads still succeed. Awaitility is provided transitively by `spring-boot-starter-test`.

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=IntegrationCacheValkeyTest`
Expected: FAIL — `IntegrationCache` does not exist (compilation error). (Docker must be running.)

- [ ] **Step 4: Write the implementation**

```java
package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Two-tier Valkey-backed cache for adapter {@link FetchResult}s (ADR-005). The coordinator (T07
 * plan 02) is its only in-track caller.
 *
 * <p>Reads are total — any Valkey error or unreadable payload yields {@link Optional#empty()} (a
 * miss), never an exception. Writes happen only on a successful fetch and populate both tiers; a
 * write failure is logged and swallowed so a cache outage never fails an otherwise-good fetch. The
 * never-overwrite-good-stale invariant is structural: a failed fetch simply never calls {@link
 * #writeOnSuccess}.
 */
@Component
public class IntegrationCache {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCache.class);

  private final StringRedisTemplate redis;
  private final FetchResultCodec codec;
  private final CacheProperties properties;

  public IntegrationCache(StringRedisTemplate redis, CacheProperties properties) {
    this.redis = redis;
    this.properties = properties;
    this.codec = new FetchResultCodec();
  }

  /** Fresh-tier read: a {@link FetchResult} within fresh TTL, or empty. Never returns stale data. */
  public Optional<FetchResult> readFresh(String orgId, String productName) {
    return read(CacheTier.FRESH, orgId, productName).flatMap(codec::decode);
  }

  /** Stale-tier read: a distinct operation the coordinator's failure path calls. */
  public Optional<StaleEntry> readStale(String orgId, String productName) {
    return read(CacheTier.STALE, orgId, productName)
        .flatMap(codec::decode)
        .map(result -> new StaleEntry(result, result.fetchedAt()));
  }

  /** Write-on-success: populate fresh AND refresh stale. The only write path. */
  public void writeOnSuccess(String orgId, String productName, FetchResult result) {
    String json = codec.encode(result);
    try {
      redis
          .opsForValue()
          .set(CacheKey.of(CacheTier.FRESH, orgId, productName), json, properties.freshTtl());
      redis
          .opsForValue()
          .set(CacheKey.of(CacheTier.STALE, orgId, productName), json, properties.staleTtl());
    } catch (DataAccessException e) {
      // A cache-write failure must never fail an otherwise-successful fetch.
      log.warn("Valkey write failed for {}:{}; continuing without caching", orgId, productName, e);
    }
  }

  private Optional<String> read(CacheTier tier, String orgId, String productName) {
    try {
      return Optional.ofNullable(redis.opsForValue().get(CacheKey.of(tier, orgId, productName)));
    } catch (DataAccessException e) {
      // Valkey unreachable / timeout → a miss, never a thrown exception on the read path.
      log.debug("Valkey read failed for {} {}:{}; treating as miss", tier, orgId, productName, e);
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=IntegrationCacheValkeyTest`
Expected: PASS (all tests). Testcontainers pulls `valkey/valkey:8-alpine` on first run.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/cache/IntegrationCache.java src/test/java/com/rapid7/integrationregistry/cache/IntegrationCacheValkeyTest.java src/test/java/com/rapid7/integrationregistry/cache/ValkeyTestContainer.java
git commit -m "feat(cache): add IntegrationCache with two-tier Valkey reads/writes"
```

---

## Task 8: Cache layer ArchUnit rule

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`

- [ ] **Step 1: Add the rule constant**

In `LayerDependencyRules.java`, add after `coordinatorLayer_shouldNotDependOnDisallowedLayers` (around line 44):

```java
  static final ArchRule cacheLayer_shouldNotDependOnDisallowedLayers =
      noClasses()
          .that()
          .resideInAPackage("..cache..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..controller..", "..service..", "..aggregator..", "..coordinator..", "..mapping..");
```

- [ ] **Step 2: Wire the `@ArchTest`**

In `LayerDependencyRulesTest.java`, add after `coordinatorLayer_shouldNotDependOnDisallowedLayers` (around line 27):

```java
  @ArchTest
  static final ArchRule cacheLayer_shouldNotDependOnDisallowedLayers =
      LayerDependencyRules.cacheLayer_shouldNotDependOnDisallowedLayers;
```

- [ ] **Step 3: Run the architecture tests**

Run: `./mvnw -q test -Dtest=LayerDependencyRulesTest`
Expected: PASS — the cache layer depends only on `adapter` (FetchResult) + Spring/Lettuce/Jackson, none of the disallowed layers.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/architecture/
git commit -m "test(arch): enforce cache layer boundary"
```

---

## Task 9: Application config

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add cache TTL defaults and a Redis connection block**

Add the `integration-registry.cache` keys under the existing `integration-registry:` block, and a top-level `spring.data.redis` block. The default-profile `application.yaml` keeps localhost defaults; per-environment host/port come from deploy env. Add to the `integration-registry:` map:

```yaml
  cache:
    fresh-ttl: 5m
    stale-ttl: 24h
```

And add under the top-level `spring:` block (the first document, alongside `application`):

```yaml
  data:
    redis:
      host: ${REGISTRY_VALKEY_HOST:localhost}
      port: ${REGISTRY_VALKEY_PORT:6379}
      timeout: 250ms
```

- [ ] **Step 2: Verify the app context still starts**

Run: `./mvnw -q test -Dtest=IntegrationRegistryApplicationTests`
Expected: PASS — the full-context test still loads with the new config keys. It already seeds the vendor-mapping bundle, mocks `S3Client`, and supplies the ICON base-urls (see the existing test). The new `spring.data.redis.*` keys do NOT require a live Valkey at boot: Lettuce connects lazily, so `contextLoads()` passes without a running server. (The cache's real-server behavior is covered by the `@DataRedisTest` slice in Task 7, not here.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config(cache): add Valkey connection and TTL defaults"
```

---

## Task 10: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run Spotless formatting**

Run: `./mvnw -q spotless:apply`
Expected: reformats any new files to Google Java Format. Review the diff.

- [ ] **Step 2: Run the full build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — JUnit (incl. Testcontainers cache tests), ArchUnit (incl. the new cache rule), PMD, Spotless all green. Docker must be running.

- [ ] **Step 3: Commit any formatting changes**

```bash
git add -A
git commit -m "style(cache): apply Google Java Format" || echo "nothing to format"
```

---

## Self-review notes (author)

- **Spec coverage:** every spec section maps to a task — package layout (T2,T8), component surface (T7), `StaleEntry` (T4), keys (T3), TTLs/config (T6,T9), serialization (T5), data flow + error handling (T7 impl + tests), testing strategy (T5,T6,T7,T8), dependencies (T1), new ArchUnit rule (T8).
- **Type consistency:** `readFresh→Optional<FetchResult>`, `readStale→Optional<StaleEntry>`, `writeOnSuccess(String,String,FetchResult)`, `CacheKey.of(CacheTier,String,String)→String`, `FetchResultCodec.encode(FetchResult)→String` / `decode(String)→Optional<FetchResult>`, `CacheProperties.freshTtl()/staleTtl()` — used identically across tasks.
- **Test-isolation decision (verified against the codebase):** the existing `@SpringBootTest` tests (`IntegrationRegistryApplicationTests`, `VendorMappingBootIntegrationTest`) require a seeded vendor-mapping bundle + `@MockitoBean S3Client` + ICON `base-url`/`icon-base` (fail-fast props) just to boot. The cache test deliberately avoids all that by using the `@DataRedisTest` slice (T7), which auto-configures only `StringRedisTemplate` + the cache beans against Testcontainers Valkey. This is both correct isolation and avoids coupling the cache test to unrelated boot config.
- **Open risk to verify during execution:** confirm `@DataRedisTest` in Spring Boot 4 still registers `StringRedisTemplate` and honors `@DynamicPropertySource` from the `ValkeyTestContainer` superclass (it should — the slice includes Redis auto-config). If the slice annotation name differs in Boot 4, fall back to a plain `@SpringBootTest(classes = {IntegrationCache.class, CacheProperties.class})` with explicit Redis auto-config import — still avoiding the full app context.
