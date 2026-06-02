# FanOutCoordinator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `FanOutCoordinator` that dispatches all registered `IntegrationAdapter`s in parallel under per-adapter and total-request deadlines, isolates failures, applies the stale-tier fallback decision tree against the plan-01 cache, and returns a structured `ProductOutcome` list for T09 to serialize.

**Architecture:** A `@Component` in the `coordinator` package depending only on `cache` + `adapter` (ArchUnit-enforced). It owns a per-call virtual-thread executor (ADR-002), enforces both timeouts via `Future.get(remaining, MILLISECONDS)` against an absolute deadline, and classifies each future independently — the failure-isolation boundary. Output is a sealed `ProductOutcome` (`Served` / `Unavailable`) making illegal states unrepresentable.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, Mockito, AssertJ, Maven. Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), `@ConfigurationProperties`.

**Spec:** `docs/superpowers/specs/2026-06-02-fan-out-coordinator-design.md`

---

## File structure

| File | Responsibility |
|---|---|
| `coordinator/ProductOutcome.java` | Sealed result type: `Served` (fresh/stale data) + `Unavailable` (omitted). T09's stagger-point contract. |
| `coordinator/CoordinatorProperties.java` | `@ConfigurationProperties` — total deadline, default per-adapter timeout, per-product timeout map. |
| `coordinator/CoordinatorConfiguration.java` | `@Configuration @EnableConfigurationProperties(CoordinatorProperties.class)` — mirrors `CacheConfiguration`. |
| `coordinator/FanOutCoordinator.java` | The orchestration: fresh-read, parallel dispatch, timeout/deadline enforcement, classification, stale fallback, cache write. |
| `coordinator/package-info.java` | (exists) update doc if needed. |
| `src/main/resources/application.yaml` | `spring.threads.virtual.enabled` + `integration-registry.coordinator.*`. |
| `coordinator/ProductOutcomeTest.java` | Invariant tests for the sealed type. |
| `coordinator/CoordinatorPropertiesBindingTest.java` | Binding + defaulting + validation + accessor. |
| `coordinator/CoordinatorAdapterFixtures.java` | Synthetic in-process `IntegrationAdapter` doubles for coordinator tests. |
| `coordinator/FanOutCoordinatorTest.java` | Behavior tests: fresh hit, each reason, stale branches, no-write-on-failure, timeout, deadline, concurrency. |

Tasks are ordered so each leaves the build green: types first (1–3), config (4), then the coordinator behavior built test-by-test (5–13), then wiring + final verify (14–15).

---

### Task 1: `ProductOutcome` sealed type — `Served` variant + invariant tests

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java`

- [ ] **Step 1: Write the failing test**

Create `ProductOutcomeTest.java`:

```java
package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductOutcomeTest {

  private static final Instant FETCHED_AT = Instant.parse("2026-06-01T12:00:00Z");

  @Test
  void served_shouldExposeFields_whenFreshHit() {
    // Arrange / Act
    ProductOutcome.Served served =
        new ProductOutcome.Served(
            "InsightConnect", List.of(), FETCHED_AT, true, false, Optional.empty());

    // Assert
    assertThat(served.productName()).isEqualTo("InsightConnect");
    assertThat(served.cacheHitPerProduct()).isTrue();
    assertThat(served.stale()).isFalse();
    assertThat(served.staleSince()).isEmpty();
  }

  @Test
  void served_shouldRejectStaleWithoutStaleSince() {
    // Act / Assert: stale=true but staleSince empty is an illegal state
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightIDR", List.of(), FETCHED_AT, false, true, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void served_shouldRejectStaleSinceWhenNotStale() {
    // Act / Assert: staleSince present but stale=false is an illegal state
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightIDR", List.of(), FETCHED_AT, false, false, Optional.of(FETCHED_AT)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void served_shouldDefensivelyCopyIntegrations() {
    // Arrange
    var mutable = new java.util.ArrayList<NormalizedIntegration>();

    // Act
    ProductOutcome.Served served =
        new ProductOutcome.Served(
            "InsightConnect", mutable, FETCHED_AT, true, false, Optional.empty());
    mutable.add(null); // mutate the source list after construction

    // Assert: the record's copy is unaffected
    assertThat(served.integrations()).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ProductOutcomeTest`
Expected: COMPILE FAILURE — `ProductOutcome` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `ProductOutcome.java` (Served variant only for now — `Unavailable` lands in Task 2; the `permits` clause references both, so add the `Unavailable` stub in the same file to compile):

```java
package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured per-product result of a fan-out dispatch — the stagger-point contract T09's {@code
 * VendorService} assembles {@code unavailable_products[]} and {@code metadata} from (RFC-001
 * §Supporting types).
 *
 * <p>Stale data is carried on {@link Served} with {@code stale == true} (a stale serve still
 * contributes integrations to the grid); {@link Unavailable} is the genuine omission case. T09
 * computes {@code metadata.cache_hit} as true iff every outcome is a {@code Served} with {@code
 * cacheHitPerProduct == true}, and {@code metadata.as_of} as the oldest {@code fetchedAt} across
 * {@code Served} outcomes.
 */
public sealed interface ProductOutcome
    permits ProductOutcome.Served, ProductOutcome.Unavailable {

  /** The canonical {@code productName()} of the adapter that produced this outcome. */
  String productName();

  /**
   * A product whose integrations are present in the response — served either fresh (from the
   * adapter or the fresh cache tier) or stale (from the stale tier on adapter failure).
   *
   * @param cacheHitPerProduct true only on a fresh-tier hit (no adapter call this request)
   * @param stale true when served from the stale tier; {@code staleSince} is then present
   * @param staleSince the original product fetch time of the stale data; present iff {@code stale}
   */
  record Served(
      String productName,
      List<NormalizedIntegration> integrations,
      Instant fetchedAt,
      boolean cacheHitPerProduct,
      boolean stale,
      Optional<Instant> staleSince)
      implements ProductOutcome {

    public Served {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(integrations, "integrations");
      Objects.requireNonNull(fetchedAt, "fetchedAt");
      Objects.requireNonNull(staleSince, "staleSince");
      if (stale != staleSince.isPresent()) {
        throw new IllegalArgumentException("staleSince must be present iff stale is true");
      }
      integrations = List.copyOf(integrations);
    }
  }

  /** Placeholder — fully specified in Task 2. */
  record Unavailable(String productName, String reason, boolean stale) implements ProductOutcome {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ProductOutcomeTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java
git commit -m "feat(coordinator): add ProductOutcome.Served sealed variant"
```

---

### Task 2: `ProductOutcome.Unavailable` variant invariants

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java`

- [ ] **Step 1: Add the failing tests**

Append to `ProductOutcomeTest.java`:

```java
  @Test
  void unavailable_shouldExposeReason_whenOmitted() {
    // Arrange / Act
    ProductOutcome.Unavailable unavailable =
        new ProductOutcome.Unavailable("InsightIDR", "timeout", false);

    // Assert
    assertThat(unavailable.productName()).isEqualTo("InsightIDR");
    assertThat(unavailable.reason()).isEqualTo("timeout");
    assertThat(unavailable.stale()).isFalse();
  }

  @Test
  void unavailable_shouldRejectTrueStale() {
    // Act / Assert: Unavailable always means omitted — stale must be false
    assertThatThrownBy(() -> new ProductOutcome.Unavailable("InsightIDR", "timeout", true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unavailable_shouldRejectBlankReason() {
    // Act / Assert
    assertThatThrownBy(() -> new ProductOutcome.Unavailable("InsightIDR", "  ", false))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ProductOutcomeTest`
Expected: FAIL — `Unavailable` placeholder enforces no invariants (true-stale and blank-reason tests fail).

- [ ] **Step 3: Replace the `Unavailable` placeholder**

In `ProductOutcome.java`, replace the placeholder record with:

```java
  /**
   * A product omitted from the response: the adapter failed (timeout, upstream_5xx, auth_failure)
   * or returned empty ({@code no_data}) and no usable stale entry existed. {@code stale} is always
   * false here — when stale data IS available it is surfaced as a {@link Served} with {@code stale
   * == true}, not here.
   *
   * @param reason one of {@code timeout | upstream_5xx | auth_failure | no_data} (RFC-001
   *     §Supporting types); for adapter exceptions this is {@code AdapterException.reasonCode()},
   *     never re-derived at the catch site (ADR-001)
   */
  record Unavailable(String productName, String reason, boolean stale) implements ProductOutcome {

    public Unavailable {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(reason, "reason");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      if (stale) {
        throw new IllegalArgumentException("Unavailable.stale must be false (omitted, not served)");
      }
    }
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ProductOutcomeTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java
git commit -m "feat(coordinator): add ProductOutcome.Unavailable invariants"
```

---

### Task 3: `CoordinatorProperties` with defaults, validation, and accessor

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/coordinator/CoordinatorProperties.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/CoordinatorPropertiesBindingTest.java`

- [ ] **Step 1: Write the failing test**

Create `CoordinatorPropertiesBindingTest.java` (plain unit test of the record, no Spring context — mirrors how `CacheProperties` validation is unit-tested):

```java
package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoordinatorPropertiesBindingTest {

  @Test
  void constructor_shouldApplyDefaults_whenNullsGiven() {
    // Act
    CoordinatorProperties props = new CoordinatorProperties(null, null, null);

    // Assert: RFC starting points / safe defaults
    assertThat(props.totalDeadline()).isEqualTo(Duration.ofSeconds(20));
    assertThat(props.defaultPerAdapterTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(props.perAdapterTimeout()).isEmpty();
  }

  @Test
  void perAdapterTimeout_shouldReturnEntry_whenProductPresent() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(
            Duration.ofSeconds(20),
            Duration.ofSeconds(10),
            Map.of("InsightConnect", Duration.ofSeconds(5), "InsightIDR", Duration.ofSeconds(15)));

    // Act / Assert
    assertThat(props.perAdapterTimeoutFor("InsightConnect")).isEqualTo(Duration.ofSeconds(5));
    assertThat(props.perAdapterTimeoutFor("InsightIDR")).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void perAdapterTimeout_shouldFallBackToDefault_whenProductAbsent() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(Duration.ofSeconds(20), Duration.ofSeconds(10), Map.of());

    // Act / Assert
    assertThat(props.perAdapterTimeoutFor("Surface Command")).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void constructor_shouldRejectNonPositiveTotalDeadline() {
    // Act / Assert
    assertThatThrownBy(() -> new CoordinatorProperties(Duration.ZERO, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_shouldRejectNonPositivePerAdapterEntry() {
    // Act / Assert
    assertThatThrownBy(
            () ->
                new CoordinatorProperties(
                    null, null, Map.of("InsightIDR", Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void perAdapterTimeout_shouldBeUnmodifiable() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(null, null, Map.of("InsightIDR", Duration.ofSeconds(15)));

    // Act / Assert
    assertThatThrownBy(() -> props.perAdapterTimeout().put("x", Duration.ofSeconds(1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=CoordinatorPropertiesBindingTest`
Expected: COMPILE FAILURE — `CoordinatorProperties` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `CoordinatorProperties.java`:

```java
package com.rapid7.integrationregistry.coordinator;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fan-out timing configuration, bound from {@code integration-registry.coordinator.*}.
 *
 * <p>Defaults are RFC-001 §Operational-defaults starting points (Q5: numbers deferred to staging,
 * shape pinned, every value per-environment configurable). {@code perAdapterTimeout} is keyed by
 * the adapter's canonical {@code productName()} ({@code InsightConnect}, {@code InsightIDR}, …); a
 * product with no entry falls back to {@code defaultPerAdapterTimeout}.
 */
@ConfigurationProperties("integration-registry.coordinator")
public record CoordinatorProperties(
    Duration totalDeadline,
    Duration defaultPerAdapterTimeout,
    Map<String, Duration> perAdapterTimeout) {

  private static final Duration DEFAULT_TOTAL_DEADLINE = Duration.ofSeconds(20);
  private static final Duration DEFAULT_PER_ADAPTER_TIMEOUT = Duration.ofSeconds(10);

  public CoordinatorProperties {
    if (totalDeadline == null) {
      totalDeadline = DEFAULT_TOTAL_DEADLINE;
    }
    if (defaultPerAdapterTimeout == null) {
      defaultPerAdapterTimeout = DEFAULT_PER_ADAPTER_TIMEOUT;
    }
    perAdapterTimeout = perAdapterTimeout == null ? Map.of() : Map.copyOf(perAdapterTimeout);

    requirePositive(totalDeadline, "totalDeadline");
    requirePositive(defaultPerAdapterTimeout, "defaultPerAdapterTimeout");
    perAdapterTimeout.forEach((product, timeout) -> requirePositive(timeout, "perAdapterTimeout." + product));
  }

  /** The configured per-adapter timeout for {@code productName}, or the default when unset. */
  public Duration perAdapterTimeoutFor(String productName) {
    return perAdapterTimeout.getOrDefault(productName, defaultPerAdapterTimeout);
  }

  private static void requirePositive(Duration value, String field) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=CoordinatorPropertiesBindingTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/CoordinatorProperties.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/CoordinatorPropertiesBindingTest.java
git commit -m "feat(coordinator): add CoordinatorProperties with timeout defaults"
```

---

### Task 4: `CoordinatorConfiguration` + `application.yaml` wiring

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/coordinator/CoordinatorConfiguration.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Create the configuration class**

Mirrors `cache/CacheConfiguration.java`:

```java
package com.rapid7.integrationregistry.coordinator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link CoordinatorProperties} (mirrors {@code CacheConfiguration}). */
@Configuration
@EnableConfigurationProperties(CoordinatorProperties.class)
public class CoordinatorConfiguration {}
```

- [ ] **Step 2: Add config to `application.yaml`**

In the root document, add `spring.threads.virtual.enabled` under `spring:` and the coordinator block under `integration-registry:`. Final `spring:` and `integration-registry:` sections:

```yaml
spring:
  application:
    name: integration-registry
  threads:
    virtual:
      enabled: true   # ADR-002: blocking RestClient adapters are dispatched in parallel by the
                      # FanOutCoordinator; virtual threads make those blocking calls truly concurrent.
  data:
    redis:
      host: ${REGISTRY_VALKEY_HOST:localhost}
      port: ${REGISTRY_VALKEY_PORT:6379}
      timeout: 250ms
```

```yaml
integration-registry:
  cache:
    fresh-ttl: 5m
    stale-ttl: 24h
  coordinator:
    total-deadline: 20s
    default-per-adapter-timeout: 10s
    per-adapter-timeout:
      InsightConnect: 5s
      InsightIDR: 15s
  vendor-mapping:
    cache-dir: ${java.io.tmpdir}/integration-registry/vendor-mapping
  insightconnect:
    timeout: 5s
```

(Preserve the existing comments in the `vendor-mapping` and `insightconnect` blocks.)

- [ ] **Step 3: Verify the context still loads**

Run: `./mvnw -q test -Dtest=IntegrationRegistryApplicationTests`
Expected: PASS — the Spring context boots with the new properties bean and virtual threads enabled.

> Note: `IntegrationRegistryApplicationTests` may require Docker (Valkey Testcontainer) if it loads the full context. If it fails for a Docker reason rather than a wiring reason, note it and rely on Task 15's full `./mvnw verify` (Docker present) for the context check.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/CoordinatorConfiguration.java \
        src/main/resources/application.yaml
git commit -m "feat(coordinator): register CoordinatorProperties and enable virtual threads"
```

---

### Task 5: Synthetic adapter fixtures for coordinator tests

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/coordinator/CoordinatorAdapterFixtures.java`

No test of its own — it's a test helper consumed by Task 6+. Verified by compiling under Task 6.

- [ ] **Step 1: Create the fixtures helper**

```java
package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * Synthetic in-process {@link IntegrationAdapter} doubles for {@link FanOutCoordinator} tests. The
 * coordinator is adapter-agnostic, so these real (non-mock) adapters exercise dispatch, timeout,
 * and classification without any HTTP. Each tracks its invocation count so tests can assert a fresh
 * cache hit skipped the adapter call.
 */
final class CoordinatorAdapterFixtures {

  private CoordinatorAdapterFixtures() {}

  static final Instant FETCHED_AT = Instant.parse("2026-06-01T12:00:00Z");

  static FetchResult sampleResult(String productName, Instant fetchedAt) {
    NormalizedIntegration integration =
        new NormalizedIntegration(
            "id-1",
            new SourceIdentifier("plugin_name", "jira"),
            productName,
            "Automation Plugin",
            null,
            IntegrationStatus.HEALTHY,
            null,
            "https://example/integrations/id-1",
            "org-123");
    return new FetchResult(List.of(integration), fetchedAt);
  }

  /** Base double exposing a productName and an invocation counter. */
  abstract static class CountingAdapter implements IntegrationAdapter {
    final AtomicInteger calls = new AtomicInteger();
    private final String productName;

    CountingAdapter(String productName) {
      this.productName = productName;
    }

    @Override
    public String productName() {
      return productName;
    }

    int callCount() {
      return calls.get();
    }
  }

  /** Returns a non-empty result immediately. */
  static CountingAdapter success(String productName) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        return sampleResult(productName, FETCHED_AT);
      }
    };
  }

  /** Returns an empty (but successful) result. */
  static CountingAdapter empty(String productName) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        return new FetchResult(List.of(), FETCHED_AT);
      }
    };
  }

  /** Throws the supplied adapter exception. */
  static CountingAdapter throwing(String productName, AdapterException toThrow) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders)
          throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
        calls.incrementAndGet();
        switch (toThrow) {
          case AdapterTimeoutException e -> throw e;
          case AdapterAuthException e -> throw e;
          case AdapterUpstreamException e -> throw e;
          default -> throw new IllegalStateException("unexpected adapter exception type");
        }
      }
    };
  }

  /** Sleeps {@code sleepMillis} before returning a result — used to trip per-adapter timeouts. */
  static CountingAdapter slow(String productName, long sleepMillis) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("interrupted", e);
        }
        return sampleResult(productName, FETCHED_AT);
      }
    };
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/CoordinatorAdapterFixtures.java
git commit -m "test(coordinator): add synthetic IntegrationAdapter fixtures"
```

---

### Task 6: `FanOutCoordinator` — fresh-tier hit serves without an adapter call

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinator.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Write the failing test**

Create `FanOutCoordinatorTest.java`:

```java
package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.cache.StaleEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class FanOutCoordinatorTest {

  private static final String ORG = "org-123";
  private static final Instant FETCHED_AT = CoordinatorAdapterFixtures.FETCHED_AT;

  private IntegrationCache cache;

  @BeforeEach
  void setUp() {
    cache = mock(IntegrationCache.class);
    // Default: every fresh/stale read is a miss unless a test overrides it.
    when(cache.readFresh(anyString(), anyString())).thenReturn(Optional.empty());
    when(cache.readStale(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private CoordinatorProperties props() {
    return new CoordinatorProperties(
        Duration.ofSeconds(20), Duration.ofSeconds(10), Map.of());
  }

  private FanOutCoordinator coordinator(Set<? extends IntegrationAdapterMarker> ignored) {
    throw new UnsupportedOperationException("placeholder — see real helper below");
  }

  // Marker only so the placeholder above compiles away; real tests build the coordinator inline.
  private interface IntegrationAdapterMarker {}

  @Test
  void fetchAll_shouldServeFromFreshTier_withoutCallingAdapter() {
    // Arrange: fresh hit for InsightConnect
    FetchResult cached = CoordinatorAdapterFixtures.sampleResult("InsightConnect", FETCHED_AT);
    when(cache.readFresh(ORG, "InsightConnect")).thenReturn(Optional.of(cached));
    var adapter = CoordinatorAdapterFixtures.success("InsightConnect");
    FanOutCoordinator coordinator =
        new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: served fresh, adapter never invoked, cache-hit flag set
    assertThat(adapter.callCount()).isZero();
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.productName()).isEqualTo("InsightConnect");
              assertThat(served.cacheHitPerProduct()).isTrue();
              assertThat(served.stale()).isFalse();
              assertThat(served.fetchedAt()).isEqualTo(FETCHED_AT);
            });
  }
}
```

> Delete the `coordinator(Set…)` placeholder and `IntegrationAdapterMarker` before committing — they exist only to illustrate; the real test builds `new FanOutCoordinator(Set.of(adapter), cache, props())` directly. (Listed explicitly so the engineer removes the stubs.)

Clean version: remove the two placeholder members; keep `setUp`, `props()`, and the test method.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: COMPILE FAILURE — `FanOutCoordinator` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `FanOutCoordinator.java`. This first cut handles fresh hits and a basic miss→dispatch→serve/write; later tasks layer in timeouts, stale fallback, and classification. Write the full structure now (it's cohesive and hard to grow incrementally without churn), but keep behavior verifiable by the tests added per task:

```java
package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.cache.StaleEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Request-time orchestration seam between the {@link IntegrationAdapter}s and the read API (T09).
 * Dispatches all registered adapters in parallel under a per-adapter timeout and a total request
 * deadline, isolates per-adapter failures, applies the stale-tier fallback decision tree against
 * the {@link IntegrationCache}, and returns a structured {@link ProductOutcome} per product.
 *
 * <p>The single invariant: one adapter's failure never fails the request (RFC-001 §Fan-out
 * coordinator). No retry — one {@code fetch} per adapter per request. Failure reasons are sourced
 * from {@link AdapterException#reasonCode()} (ADR-001), never re-derived here.
 *
 * <p>Adapters use blocking {@code RestClient} (ADR-002); a per-call virtual-thread executor makes
 * the dispatch truly concurrent. Requires {@code spring.threads.virtual.enabled=true}.
 */
@Component
public class FanOutCoordinator {

  private static final Logger log = LoggerFactory.getLogger(FanOutCoordinator.class);
  private static final String REASON_TIMEOUT = "timeout";
  private static final String REASON_NO_DATA = "no_data";

  private final Set<IntegrationAdapter> adapters;
  private final IntegrationCache cache;
  private final CoordinatorProperties properties;

  public FanOutCoordinator(
      Set<IntegrationAdapter> adapters, IntegrationCache cache, CoordinatorProperties properties) {
    this.adapters = adapters;
    this.cache = cache;
    this.properties = properties;
  }

  /**
   * Fetch every registered product's integrations for {@code orgId}, in parallel. Returns one
   * {@link ProductOutcome} per adapter; never throws for a per-adapter failure.
   */
  public List<ProductOutcome> fetchAll(String orgId, HttpHeaders authHeaders) {
    List<ProductOutcome> outcomes = new ArrayList<>();
    List<Dispatch> dispatches = new ArrayList<>();

    // Phase 1: fresh-tier reads. A fresh hit is served directly — no adapter task submitted.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (IntegrationAdapter adapter : adapters) {
        String product = adapter.productName();
        Optional<FetchResult> fresh = cache.readFresh(orgId, product);
        if (fresh.isPresent()) {
          outcomes.add(servedFresh(product, fresh.get()));
        } else {
          Future<FetchResult> future =
              executor.submit(() -> adapter.fetch(orgId, authHeaders));
          dispatches.add(new Dispatch(product, future));
        }
      }

      // Phase 2: await each dispatched adapter under per-adapter timeout AND total deadline.
      Instant deadline = Instant.now().plus(properties.totalDeadline());
      for (Dispatch dispatch : dispatches) {
        outcomes.add(awaitAndClassify(dispatch, orgId, deadline));
      }
    }
    return outcomes;
  }

  private ProductOutcome awaitAndClassify(Dispatch dispatch, String orgId, Instant deadline) {
    String product = dispatch.product();
    long perAdapterMs = properties.perAdapterTimeoutFor(product).toMillis();
    long untilDeadlineMs = Duration.between(Instant.now(), deadline).toMillis();
    long waitMs = Math.max(0, Math.min(perAdapterMs, untilDeadlineMs));
    try {
      FetchResult result = dispatch.future().get(waitMs, TimeUnit.MILLISECONDS);
      return classifySuccess(product, orgId, result);
    } catch (TimeoutException e) {
      dispatch.future().cancel(true);
      log.debug("Adapter {} exceeded its deadline; treating as timeout", product);
      return staleOrUnavailable(orgId, product, REASON_TIMEOUT);
    } catch (ExecutionException e) {
      return classifyFailure(orgId, product, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      dispatch.future().cancel(true);
      return staleOrUnavailable(orgId, product, REASON_TIMEOUT);
    }
  }

  private ProductOutcome classifySuccess(String product, String orgId, FetchResult result) {
    if (result.integrations().isEmpty()) {
      // Successful-but-empty: serve stale if usable, else no_data. Empty success is NOT cached.
      return staleOrUnavailable(orgId, product, REASON_NO_DATA);
    }
    cache.writeOnSuccess(orgId, product, result);
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), false, false, Optional.empty());
  }

  private ProductOutcome classifyFailure(String orgId, String product, Throwable cause) {
    if (cause instanceof AdapterException adapterException) {
      return staleOrUnavailable(orgId, product, adapterException.reasonCode());
    }
    // An unexpected (non-AdapterException) cause is an adapter-contract violation, not partial
    // unavailability — surface it so T09's @ControllerAdvice maps it to 500. Never silently dropped.
    throw new IllegalStateException(
        "Adapter " + product + " failed with an unexpected exception", cause);
  }

  /** Stale-fallback decision: serve stale within window, else omit with the given reason. */
  private ProductOutcome staleOrUnavailable(String orgId, String product, String reason) {
    Optional<StaleEntry> stale = cache.readStale(orgId, product);
    if (stale.isPresent()) {
      StaleEntry entry = stale.get();
      return new ProductOutcome.Served(
          product,
          entry.result().integrations(),
          entry.result().fetchedAt(),
          false,
          true,
          Optional.of(entry.staleSince()));
    }
    return new ProductOutcome.Unavailable(product, reason, false);
  }

  private ProductOutcome servedFresh(String product, FetchResult result) {
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), true, false, Optional.empty());
  }

  private record Dispatch(String product, Future<FetchResult> future) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS (fresh-hit test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinator.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "feat(coordinator): FanOutCoordinator serves fresh-tier hit without adapter call"
```

---

### Task 7: Cache miss + adapter success writes fresh and serves

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing test**

```java
  @Test
  void fetchAll_shouldFetchWriteAndServe_whenFreshMissAndAdapterSucceeds() {
    // Arrange: fresh + stale miss (defaults), adapter returns data
    var adapter = CoordinatorAdapterFixtures.success("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: adapter called once, fresh tier written, served non-stale non-cache-hit
    assertThat(adapter.callCount()).isEqualTo(1);
    verify(cache).writeOnSuccess(eq(ORG), eq("InsightConnect"), any(FetchResult.class));
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.cacheHitPerProduct()).isFalse();
              assertThat(served.stale()).isFalse();
              assertThat(served.integrations()).hasSize(1);
            });
  }
```

Add the missing static import at the top of the file:

```java
import static org.mockito.ArgumentMatchers.eq;
```

- [ ] **Step 2: Run test to verify it passes immediately**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — Task 6's implementation already covers this path. (This test pins the behavior; no implementation change needed. If it fails, fix `classifySuccess`.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): fresh miss + adapter success writes and serves"
```

---

### Task 8: Each adapter exception maps to its `reason` (timeout, upstream_5xx, auth_failure)

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing tests**

```java
  @Test
  void fetchAll_shouldMapTimeoutException_toTimeoutReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing(
            "InsightIDR", new AdapterTimeoutException("boom"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("timeout"));
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldMapUpstreamException_toUpstream5xxReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing(
            "InsightIDR", new AdapterUpstreamException("503"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("upstream_5xx"));
  }

  @Test
  void fetchAll_shouldMapAuthException_toAuthFailureReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterAuthException("401"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("auth_failure"));
  }
```

Add the imports at the top:

```java
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — `classifyFailure` → `reasonCode()` already covers this. If any fail, fix `classifyFailure`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): adapter exceptions map to unavailable_products reasons"
```

---

### Task 9: Empty successful fetch + no stale → `no_data`, not cached

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing test**

```java
  @Test
  void fetchAll_shouldReturnNoData_whenEmptySuccessAndNoStale() {
    // Arrange: adapter returns empty, no stale entry
    var adapter = CoordinatorAdapterFixtures.empty("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: no_data, and the empty success is NOT written to cache
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("no_data"));
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — `classifySuccess` routes empty → `staleOrUnavailable(…, "no_data")` with no write. If it fails, fix `classifySuccess`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): empty success with no stale yields no_data, no write"
```

---

### Task 10: Stale-tier fallback on failure — serve stale with `stale_since`

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing tests**

```java
  @Test
  void fetchAll_shouldServeStale_whenAdapterFailsAndStaleWithinWindow() {
    // Arrange: adapter times out, stale entry exists
    Instant staleSince = Instant.parse("2026-05-31T09:00:00Z");
    FetchResult staleResult =
        CoordinatorAdapterFixtures.sampleResult("InsightIDR", staleSince);
    when(cache.readStale(ORG, "InsightIDR"))
        .thenReturn(Optional.of(new StaleEntry(staleResult, staleSince)));
    var adapter =
        CoordinatorAdapterFixtures.throwing(
            "InsightIDR", new AdapterUpstreamException("503"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: served stale with staleSince populated; never overwrites the stale tier
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.stale()).isTrue();
              assertThat(served.staleSince()).contains(staleSince);
              assertThat(served.cacheHitPerProduct()).isFalse();
              assertThat(served.integrations()).hasSize(1);
            });
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldServeStale_whenEmptySuccessAndStaleWithinWindow() {
    // Arrange: adapter returns empty, but a usable stale entry exists
    Instant staleSince = Instant.parse("2026-05-31T09:00:00Z");
    FetchResult staleResult =
        CoordinatorAdapterFixtures.sampleResult("InsightConnect", staleSince);
    when(cache.readStale(ORG, "InsightConnect"))
        .thenReturn(Optional.of(new StaleEntry(staleResult, staleSince)));
    var adapter = CoordinatorAdapterFixtures.empty("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: empty-success falls back to stale rather than no_data
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class, served -> assertThat(served.stale()).isTrue());
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — `staleOrUnavailable` already serves stale when present. If it fails, fix that method.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): stale-tier fallback serves stale with stale_since"
```

---

### Task 11: Per-adapter timeout trips on a slow adapter

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing test**

```java
  @Test
  void fetchAll_shouldTimeOutSlowAdapter_whenPerAdapterTimeoutExceeded() {
    // Arrange: a 500ms-slow adapter with a 100ms per-adapter timeout, no stale → timeout/omitted
    var slow = CoordinatorAdapterFixtures.slow("InsightIDR", 500);
    CoordinatorProperties tightProps =
        new CoordinatorProperties(
            Duration.ofSeconds(20),
            Duration.ofMillis(100),
            Map.of("InsightIDR", Duration.ofMillis(100)));
    FanOutCoordinator coordinator =
        new FanOutCoordinator(Set.of(slow), cache, tightProps);

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: surfaced as a timeout, not a hang
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("timeout"));
  }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — `future.get(waitMs, …)` throws `TimeoutException` → `REASON_TIMEOUT`. Completes in ~100ms, not 500ms.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): per-adapter timeout surfaces slow adapter as timeout"
```

---

### Task 12: Total-deadline cutoff preserves a fast adapter's data

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing test**

```java
  @Test
  void fetchAll_shouldPreserveFastAdapter_whenTotalDeadlineCutsOffSlowOne() {
    // Arrange: total deadline 200ms. Fast adapter succeeds; slow adapter (800ms) is cut off.
    // Generous per-adapter timeouts so the TOTAL deadline is the binding constraint.
    var fast = CoordinatorAdapterFixtures.success("InsightConnect");
    var slow = CoordinatorAdapterFixtures.slow("InsightIDR", 800);
    CoordinatorProperties deadlineProps =
        new CoordinatorProperties(
            Duration.ofMillis(200), Duration.ofSeconds(30), Map.of());
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(fast, slow)), cache, deadlineProps);

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: both products present; fast served, slow omitted as timeout
    assertThat(outcomes).hasSize(2);
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightConnect"))
        .first()
        .isInstanceOf(ProductOutcome.Served.class);
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightIDR"))
        .first()
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("timeout"));
  }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — the absolute deadline caps the slow adapter's `get`, while the fast adapter (submitted concurrently) already completed. Total wall-time ≈ 200ms.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): total-deadline cutoff preserves fast adapter data"
```

---

### Task 13: Parallel dispatch is concurrent, not serial (virtual threads)

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Add the failing test**

```java
  @Test
  void fetchAll_shouldDispatchAdaptersConcurrently_notSerially() {
    // Arrange: two adapters each sleeping 300ms. Serial would be ~600ms; concurrent ~300ms.
    var a = CoordinatorAdapterFixtures.slow("InsightConnect", 300);
    var b = CoordinatorAdapterFixtures.slow("InsightIDR", 300);
    // Per-adapter + total budgets comfortably above 300ms so neither times out.
    CoordinatorProperties roomyProps =
        new CoordinatorProperties(
            Duration.ofSeconds(5), Duration.ofSeconds(5), Map.of());
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(a, b)), cache, roomyProps);

    // Act
    long start = System.nanoTime();
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // Assert: both served, and wall-time is far closer to one sleep than two (proves concurrency).
    assertThat(outcomes).hasSize(2).allMatch(o -> o instanceof ProductOutcome.Served);
    assertThat(elapsedMs).isLessThan(550);
  }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS — adapters run on separate virtual threads; total ≈ 300ms. A serial implementation would take ~600ms and fail the `< 550` bound.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "test(coordinator): adapters dispatch concurrently on virtual threads"
```

---

### Task 14: Update `package-info.java` and tidy

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/coordinator/package-info.java`

- [ ] **Step 1: Confirm the package doc is accurate**

The existing one-liner ("Parallel adapter dispatch, per-adapter timeouts, failure isolation, cache read/write.") is accurate. Optionally expand to note `ProductOutcome` is the T09 stagger point:

```java
/**
 * Request-time fan-out: parallel adapter dispatch, per-adapter timeouts, a total request deadline,
 * failure isolation, and the stale-tier fallback decision tree against the two-tier cache. {@link
 * com.rapid7.integrationregistry.coordinator.ProductOutcome} is the structured per-product output
 * consumed by the read API (T09).
 */
package com.rapid7.integrationregistry.coordinator;
```

- [ ] **Step 2: Verify nothing references the removed test placeholders**

Run: `grep -rn "IntegrationAdapterMarker\|UnsupportedOperationException(\"placeholder" src/test/java/com/rapid7/integrationregistry/coordinator/` — expect no matches (the Task 6 stubs were deleted).

- [ ] **Step 3: Commit (if changed)**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/package-info.java
git commit -m "docs(coordinator): expand package-info for fan-out and ProductOutcome"
```

---

### Task 15: Full verify — ArchUnit, PMD, Spotless, all tests

**Files:** none (verification only).

- [ ] **Step 1: Spotless format**

Run: `./mvnw -q spotless:apply`
Then re-stage and amend/commit any formatting-only changes if present:
```bash
git add -A && git commit -m "style(coordinator): apply Google Java Format" || echo "nothing to format"
```

- [ ] **Step 2: Full build (Docker must be running for the cache Testcontainers)**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — JUnit (incl. new coordinator tests), ArchUnit (coordinator depends only on cache + adapter; the existing `controllerLayer`/`coordinatorLayer` rules and the `ControllerWithCoordinatorDependency` negative fixture all still pass), PMD, Spotless all green.

- [ ] **Step 3: Confirm the ArchUnit coordinator rule actually exercised the new code**

The coordinator package now has real `@Component`/config classes; `coordinatorLayer_shouldNotDependOnDisallowedLayers` evaluates them. If ArchUnit fails, the coordinator imported a forbidden layer (`controller`/`service`/`aggregator`/`mapping`) — fix the import, don't weaken the rule.

- [ ] **Step 4: Final commit if anything outstanding**

```bash
git status   # expect clean
```

---

## Self-review notes

- **Spec coverage:** parallel dispatch (T6,13), per-adapter timeout (T11), total deadline (T12), failure isolation + each reason (T8,12), stale fallback incl. empty-success branch (T9,10), no-overwrite-on-failure (T8,9,10 via `verify(never)`), `ProductOutcome` sealed type + invariants (T1,2), config knobs + defaults (T3,4), virtual threads enabled (T4) and proven live (T13), ArchUnit/PMD green (T15). All spec sections covered.
- **`reason` sourcing:** always `AdapterException.reasonCode()` or the `REASON_TIMEOUT`/`REASON_NO_DATA` constants — never re-derived at the catch site (ADR-001).
- **Type consistency:** `fetchAll(String, HttpHeaders)`, `perAdapterTimeoutFor(String)`, `ProductOutcome.Served`/`.Unavailable`, `StaleEntry.result()/.staleSince()`, `IntegrationCache.readFresh/readStale/writeOnSuccess`, `FetchResult.integrations()/.fetchedAt()` — consistent across all tasks and matched against the real source.
- **Placeholder caution:** Task 6's test includes two illustrative stubs explicitly flagged for deletion before commit; called out so they don't leak into the committed test.
- **Auth-failure stale fallback:** the implementation serves stale on `auth_failure` too (any failure with a usable stale entry), per the spec's recorded autonomous decision — flagged for Phase 7 adversarial review.
