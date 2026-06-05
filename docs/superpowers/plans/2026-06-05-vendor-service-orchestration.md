# VendorService Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `VendorService`, the read-path orchestration brain that fans out via `FanOutCoordinator`, aggregates via `VendorAggregator`, and assembles the four Plan-01 response DTOs with the `metadata` block, `unavailable_products[]` envelope, and 404-vs-partial-unavailability rule.

**Architecture:** A `@Service` with no HTTP and no mapping knowledge (ArchUnit-enforced). It calls the coordinator through a new framework-neutral `OutboundAuth` carrier, reads `mapping_version` and wire categories through new `VendorAggregator` pass-throughs, and uses an injected `Clock` for the empty-`as_of` case. Three surgical seam extensions to merged T07/T08 code precede the service itself.

**Tech Stack:** Java 25, Spring Boot 4.0.6, JUnit 5, Mockito, Google Java Format (Spotless), PMD, ArchUnit.

---

## File Structure

**New production files:**
- `src/main/java/com/rapid7/integrationregistry/auth/OutboundAuth.java` — neutral header carrier
- `src/main/java/com/rapid7/integrationregistry/auth/package-info.java`
- `src/main/java/com/rapid7/integrationregistry/service/VendorService.java` — the orchestration brain
- `src/main/java/com/rapid7/integrationregistry/service/ServiceConfiguration.java` — `@Bean Clock`

**Modified production files (surgical seam extensions):**
- `coordinator/ProductOutcome.java` — add `staleReason` to `Served`
- `coordinator/OutcomeClassifier.java` — populate `staleReason`
- `coordinator/FanOutCoordinator.java` — add `fetchAll(orgId, OutboundAuth)` overload
- `aggregator/VendorAggregator.java` — add `mappingVersion()` + `wireCategoryOf(...)`

**New test files:**
- `src/test/java/com/rapid7/integrationregistry/auth/OutboundAuthTest.java`
- `src/test/java/com/rapid7/integrationregistry/service/VendorServiceTest.java`
- `src/test/java/com/rapid7/integrationregistry/service/VendorServiceFixtures.java` — fixture builders

**Modified test files:**
- `coordinator/ProductOutcomeTest.java` — staleReason invariant
- `coordinator/FanOutCoordinatorTest.java` — overload test
- `aggregator/VendorAggregatorTest.java` — mappingVersion + wireCategory tests
- `architecture/LayerDependencyRules.java` + `LayerDependencyRulesTest.java` — auth-package neutrality rule

**Note on existing `Served` construction sites:** adding a field to `ProductOutcome.Served` breaks every `new Served(...)` call. Known sites: `OutcomeClassifier` (3), and test fixtures `CoordinatorAdapterFixtures`, `CacheFetchResultFixtures`, plus any test constructing `Served` directly. Task 1 updates all of them.

---

## Task 1: Extend `ProductOutcome.Served` with `staleReason`

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java`

- [ ] **Step 1: Read the current ProductOutcomeTest to learn its style and the existing Served invariant tests**

Run: `sed -n '1,80p' src/test/java/com/rapid7/integrationregistry/coordinator/ProductOutcomeTest.java`

- [ ] **Step 2: Write the failing test for the new invariant**

Add to `ProductOutcomeTest.java`:

```java
  @Test
  void served_staleReason_mustBePresentWhenStale() {
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightConnect",
                    List.of(),
                    Instant.parse("2026-06-05T00:00:00Z"),
                    false,
                    true,
                    Optional.of(Instant.parse("2026-06-04T00:00:00Z")),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("staleReason");
  }

  @Test
  void served_staleReason_mustBeAbsentWhenNotStale() {
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightConnect",
                    List.of(),
                    Instant.parse("2026-06-05T00:00:00Z"),
                    false,
                    false,
                    Optional.empty(),
                    Optional.of("timeout")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("staleReason");
  }

  @Test
  void served_staleServe_carriesReasonAndStaleSince() {
    ProductOutcome.Served served =
        new ProductOutcome.Served(
            "InsightConnect",
            List.of(),
            Instant.parse("2026-06-05T00:00:00Z"),
            false,
            true,
            Optional.of(Instant.parse("2026-06-04T00:00:00Z")),
            Optional.of("timeout"));
    assertThat(served.staleReason()).contains("timeout");
  }
```

Ensure imports exist: `java.util.Optional`, `java.util.List`, `java.time.Instant`, `org.assertj.core.api.Assertions.assertThat`, `assertThatThrownBy`.

- [ ] **Step 3: Run the test to verify it fails to compile (constructor arity)**

Run: `./mvnw -q test -Dtest=ProductOutcomeTest`
Expected: COMPILE FAILURE — `Served` constructor expects 6 args, got 7.

- [ ] **Step 4: Add the `staleReason` field and invariant to `Served`**

In `ProductOutcome.java`, change the `Served` record. Update the Javadoc `@param` block and add `staleReason`:

```java
  /**
   * A product whose integrations are present in the response — served either fresh (from the
   * adapter or the fresh cache tier) or stale (from the stale tier on adapter failure).
   *
   * @param cacheHitPerProduct true only on a fresh-tier hit (no adapter call this request)
   * @param stale true when served from the stale tier; {@code staleSince} is then present
   * @param staleSince the original product fetch time of the stale data; present iff {@code stale}
   * @param staleReason the failure reason that triggered the stale fallback (RFC-001 §Supporting
   *     types reason enum, sourced from {@code AdapterException.reasonCode()}); present iff {@code
   *     stale}. Lets T09 populate {@code unavailable_products[].reason} for a stale serve.
   */
  record Served(
      String productName,
      List<NormalizedIntegration> integrations,
      Instant fetchedAt,
      boolean cacheHitPerProduct,
      boolean stale,
      Optional<Instant> staleSince,
      Optional<String> staleReason)
      implements ProductOutcome {

    public Served {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(integrations, "integrations");
      Objects.requireNonNull(fetchedAt, "fetchedAt");
      Objects.requireNonNull(staleSince, "staleSince");
      Objects.requireNonNull(staleReason, "staleReason");
      if (stale != staleSince.isPresent()) {
        throw new IllegalArgumentException("staleSince must be present iff stale is true");
      }
      if (stale != staleReason.isPresent()) {
        throw new IllegalArgumentException("staleReason must be present iff stale is true");
      }
      integrations = List.copyOf(integrations);
    }
  }
```

- [ ] **Step 5: Update the `OutcomeClassifier` construction sites**

In `OutcomeClassifier.java`:
- `classifySuccess` (fresh success, ~line 36): append `, Optional.empty()` as the 7th arg.
- `servedFresh` (~line 57): append `, Optional.empty()` as the 7th arg.
- `staleOrUnavailable` stale branch (~line 66): append `, Optional.of(reason)` as the 7th arg.

The stale branch now reads:

```java
      return new ProductOutcome.Served(
          product,
          entry.result().integrations(),
          entry.result().fetchedAt(),
          false,
          true,
          Optional.of(entry.staleSince()),
          Optional.of(reason));
```

- [ ] **Step 6: Fix all other `new Served(...)` sites in tests**

Run: `grep -rln "new ProductOutcome.Served\|new Served(" src/test`
For each hit, add the 7th arg: `Optional.empty()` for fresh constructions, `Optional.of("<reason>")` for any `stale=true` construction. Likely files: `CoordinatorAdapterFixtures.java`, `CacheFetchResultFixtures.java`, `FanOutCoordinatorTest.java`.

- [ ] **Step 7: Run the coordinator + cache test suites to verify everything compiles and passes**

Run: `./mvnw -q test -Dtest='ProductOutcomeTest,OutcomeClassifier*,FanOutCoordinatorTest,CacheFetchResultFixtures'`
Expected: PASS (and full compile of test sources).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/ProductOutcome.java \
        src/main/java/com/rapid7/integrationregistry/coordinator/OutcomeClassifier.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/
git commit -m "feat(coordinator): carry stale-fallback reason on ProductOutcome.Served

The stale-serve path discarded which failure triggered the fallback;
T09 needs it to populate unavailable_products[].reason. The classifier
already has the reason in scope at the stale construction site.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Create the neutral `OutboundAuth` carrier

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/auth/OutboundAuth.java`
- Create: `src/main/java/com/rapid7/integrationregistry/auth/package-info.java`
- Test: `src/test/java/com/rapid7/integrationregistry/auth/OutboundAuthTest.java`

- [ ] **Step 1: Write the failing test**

Create `OutboundAuthTest.java`:

```java
package com.rapid7.integrationregistry.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundAuthTest {

  @Test
  void empty_hasNoHeaders() {
    assertThat(OutboundAuth.empty().headers()).isEmpty();
  }

  @Test
  void of_copiesAndExposesHeaders() {
    OutboundAuth auth = OutboundAuth.of(Map.of("X-IPIMS-ORG-ID", "org-1"));
    assertThat(auth.headers()).containsEntry("X-IPIMS-ORG-ID", "org-1");
  }

  @Test
  void of_isDefensivelyCopied() {
    Map<String, String> source = new HashMap<>();
    source.put("X-IPIMS-ORG-ID", "org-1");
    OutboundAuth auth = OutboundAuth.of(source);
    source.put("X-IPIMS-ORG-ID", "tampered");
    assertThat(auth.headers()).containsEntry("X-IPIMS-ORG-ID", "org-1");
  }

  @Test
  void headers_areUnmodifiable() {
    OutboundAuth auth = OutboundAuth.of(Map.of("a", "b"));
    assertThatThrownBy(() -> auth.headers().put("c", "d"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void of_rejectsNull() {
    assertThatThrownBy(() -> OutboundAuth.of(null)).isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=OutboundAuthTest`
Expected: COMPILE FAILURE — `OutboundAuth` does not exist.

- [ ] **Step 3: Create `OutboundAuth`**

```java
package com.rapid7.integrationregistry.auth;

import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral carrier for the outbound auth headers the read path forwards to product
 * adapters. Exists so the {@code service} layer can pass auth to the {@code coordinator} without
 * importing {@code org.springframework.http.HttpHeaders} (forbidden by the RFC-001 §Spring layer
 * boundaries ArchUnit rule). The controller (Plan 03) builds it from the inbound request; the
 * coordinator converts it back to {@code HttpHeaders} at the adapter boundary.
 *
 * <p>This package is a deliberate leaf: it depends on nothing internal, so every layer may import
 * it without creating a boundary violation.
 */
public record OutboundAuth(Map<String, String> headers) {

  public OutboundAuth {
    Objects.requireNonNull(headers, "headers");
    headers = Map.copyOf(headers);
  }

  /** An auth carrier with no headers. */
  public static OutboundAuth empty() {
    return new OutboundAuth(Map.of());
  }

  /** An auth carrier wrapping a defensive copy of {@code headers}. */
  public static OutboundAuth of(Map<String, String> headers) {
    return new OutboundAuth(headers);
  }
}
```

Create `package-info.java`:

```java
/** Framework-neutral outbound-auth carrier shared across the controller, service, and coordinator. */
package com.rapid7.integrationregistry.auth;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=OutboundAuthTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/auth/ \
        src/test/java/com/rapid7/integrationregistry/auth/
git commit -m "feat(auth): add framework-neutral OutboundAuth header carrier

Lets the service layer pass outbound auth to the coordinator without
importing org.springframework.http (ArchUnit-forbidden). Leaf package
with no internal dependencies.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Add the `FanOutCoordinator.fetchAll(orgId, OutboundAuth)` overload

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinator.java`
- Test: `src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `FanOutCoordinatorTest.java` (mirror an existing fresh-tier test, but call the new overload):

```java
  @Test
  void fetchAll_outboundAuthOverload_servesFromFreshTier() {
    // Arrange: same fixture setup as fetchAll_shouldServeFromFreshTier_withoutCallingAdapter,
    // but invoke via the OutboundAuth overload.
    seedFreshTier(); // reuse whatever helper that test uses; otherwise inline the existing arrange
    List<ProductOutcome> outcomes =
        coordinator.fetchAll(ORG, OutboundAuth.of(Map.of("X-IPIMS-ORG-ID", ORG)));
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0)).isInstanceOf(ProductOutcome.Served.class);
  }
```

Add imports: `com.rapid7.integrationregistry.auth.OutboundAuth`, `java.util.Map`. If there is no `seedFreshTier()` helper, copy the exact arrange block from `fetchAll_shouldServeFromFreshTier_withoutCallingAdapter`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest#fetchAll_outboundAuthOverload_servesFromFreshTier`
Expected: COMPILE FAILURE — no `fetchAll(String, OutboundAuth)` method.

- [ ] **Step 3: Add the overload**

In `FanOutCoordinator.java`, add the import `com.rapid7.integrationregistry.auth.OutboundAuth` and a new overload that converts to `HttpHeaders` then delegates to the existing method:

```java
  /**
   * Fetch every registered product's integrations for {@code orgId}, in parallel, taking the
   * framework-neutral {@link OutboundAuth} carrier so callers in the {@code service} layer need not
   * depend on {@code org.springframework.http}. Converts the carrier to {@link HttpHeaders} at this
   * boundary and delegates to {@link #fetchAll(String, HttpHeaders)}.
   */
  public List<ProductOutcome> fetchAll(String orgId, OutboundAuth auth) {
    Objects.requireNonNull(auth, "auth");
    HttpHeaders headers = new HttpHeaders();
    auth.headers().forEach(headers::set);
    return fetchAll(orgId, headers);
  }
```

Add `import java.util.Objects;` if not present (it is used elsewhere; verify).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=FanOutCoordinatorTest`
Expected: PASS (the new test plus all existing coordinator tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinator.java \
        src/test/java/com/rapid7/integrationregistry/coordinator/FanOutCoordinatorTest.java
git commit -m "feat(coordinator): add OutboundAuth overload of fetchAll

Service-layer callers pass the neutral carrier; the coordinator converts
to HttpHeaders at the adapter boundary. Existing HttpHeaders overload and
its tests are unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Add `VendorAggregator.mappingVersion()` and `wireCategoryOf(...)` pass-throughs

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Test: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

The service must not name `mapping.VendorCategory`, so it asks the aggregator to translate a projection record's category to a contract-valid wire String. We expose one method per projection type that carries a category (`VendorServiceCard`, `VendorServiceCardNested` is a DTO not a projection — the nested card is built by the service from a `VendorServiceCard`, so a single `wireCategoryOf(VendorServiceCard)` covers both flat and nested; `VendorServiceDetail` needs its own).

- [ ] **Step 1: Write the failing tests**

Add to `VendorAggregatorTest.java`:

```java
  @Test
  void mappingVersion_passesThroughSnapshotVersion() {
    // The test's snapshot stub returns a known version; assert the aggregator surfaces it.
    assertThat(aggregator.mappingVersion()).isEqualTo(expectedMappingVersion());
  }

  @Test
  void wireCategoryOf_card_mapsCloudProviderToCloud() {
    VendorServiceCard card = cardWithCategory(VendorCategory.CLOUD_PROVIDER);
    assertThat(aggregator.wireCategoryOf(card)).isEqualTo("cloud");
  }

  @Test
  void wireCategoryOf_card_mapsIdentityToOther() {
    VendorServiceCard card = cardWithCategory(VendorCategory.IDENTITY);
    assertThat(aggregator.wireCategoryOf(card)).isEqualTo("other");
  }

  @Test
  void wireCategoryOf_card_mapsNotificationToOther() {
    VendorServiceCard card = cardWithCategory(VendorCategory.NOTIFICATION);
    assertThat(aggregator.wireCategoryOf(card)).isEqualTo("other");
  }

  @Test
  void wireCategoryOf_card_passesThroughExactMatches() {
    assertThat(aggregator.wireCategoryOf(cardWithCategory(VendorCategory.EDR))).isEqualTo("edr");
    assertThat(aggregator.wireCategoryOf(cardWithCategory(VendorCategory.SIEM))).isEqualTo("siem");
    assertThat(aggregator.wireCategoryOf(cardWithCategory(VendorCategory.ITSM))).isEqualTo("itsm");
    assertThat(aggregator.wireCategoryOf(cardWithCategory(VendorCategory.OTHER))).isEqualTo("other");
  }

  @Test
  void wireCategoryOf_detail_mapsSameAsCard() {
    VendorServiceDetail detail = detailWithCategory(VendorCategory.CLOUD_PROVIDER);
    assertThat(aggregator.wireCategoryOf(detail)).isEqualTo("cloud");
  }
```

Add a `cardWithCategory(VendorCategory)` and `detailWithCategory(VendorCategory)` helper to the test (build a minimal valid `VendorServiceCard`/`VendorServiceDetail`; reuse existing builders in the test if present). For `mappingVersion`, check what version the test's `VendorMappingSnapshot` stub already returns — use that as `expectedMappingVersion()`. Look at `StubVendorMappingSnapshot` / `MapBackedSnapshotBuilder` for the existing version.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: COMPILE FAILURE — `mappingVersion()` / `wireCategoryOf(...)` do not exist.

- [ ] **Step 3: Implement the pass-throughs**

In `VendorAggregator.java`, add a public `mappingVersion()` and the category translation. Place a private static enum-to-wire mapper and two thin public overloads:

```java
  /**
   * The {@code metadata.mapping_version} of the loaded bundle, surfaced for T09's response
   * assembly so the service layer never has to depend on {@code ..mapping..} (ArchUnit-forbidden).
   */
  public String mappingVersion() {
    return snapshot.mappingVersion();
  }

  /**
   * Translate a projection's internal {@link VendorCategory} to a contract-valid openapi
   * VendorCategory wire value. Exists so the service layer can populate the wire DTO without naming
   * {@link VendorCategory} (ArchUnit-forbidden). The internal enum and the wire enum have mismatched
   * value sets; non-overlapping internal values fold to the wire's {@code other} fallback, except
   * {@code cloud_provider} which maps to the wire's {@code cloud}.
   */
  public String wireCategoryOf(VendorServiceCard card) {
    return toWireCategory(card.vendorCategory());
  }

  /** See {@link #wireCategoryOf(VendorServiceCard)}. */
  public String wireCategoryOf(VendorServiceDetail detail) {
    return toWireCategory(detail.vendorCategory());
  }

  private static String toWireCategory(VendorCategory category) {
    return switch (category) {
      case EDR -> "edr";
      case SIEM -> "siem";
      case ITSM -> "itsm";
      case CLOUD_PROVIDER -> "cloud";
      case IDENTITY, NOTIFICATION, OTHER -> "other";
    };
  }
```

Note: `VendorCategory` is already imported (the projection records use it; verify the aggregator's import block — if missing, add `import com.rapid7.integrationregistry.mapping.VendorCategory;`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "feat(aggregator): expose mappingVersion + wire-category translation

T09's service layer cannot name ..mapping.. (ArchUnit). The aggregator,
which legally depends on mapping, translates a projection's VendorCategory
to a contract-valid wire value and surfaces the bundle version.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Add the `..auth..` neutrality ArchUnit rule

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`

- [ ] **Step 1: Read the existing rule + test wiring**

Run: `sed -n '1,60p' src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`
Learn how rules are evaluated against the imported classes there.

- [ ] **Step 2: Add the rule constant**

In `LayerDependencyRules.java`, add:

```java
  static final ArchRule authLayer_shouldBeAFrameworkNeutralLeaf =
      noClasses()
          .that()
          .resideInAPackage("..auth..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..controller..",
              "..service..",
              "..coordinator..",
              "..aggregator..",
              "..adapter..",
              "..cache..",
              "..mapping..",
              "org.springframework..");
```

- [ ] **Step 3: Add the test that evaluates it**

In `LayerDependencyRulesTest.java`, mirror an existing `@Test` that calls `<rule>.check(importedClasses)` (or `.evaluate(...)`):

```java
  @Test
  void authLayer_isAFrameworkNeutralLeaf() {
    LayerDependencyRules.authLayer_shouldBeAFrameworkNeutralLeaf.check(allClasses);
  }
```

Use the exact field name for the imported classes that the other tests in this file use (e.g. `allClasses` / `importedClasses`).

- [ ] **Step 4: Run the architecture tests**

Run: `./mvnw -q test -Dtest=LayerDependencyRulesTest`
Expected: PASS — `OutboundAuth` imports only `java.util`, so the leaf rule holds.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/architecture/
git commit -m "test(arch): assert ..auth.. is a framework-neutral leaf package

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `ServiceConfiguration` with the `Clock` bean

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/service/ServiceConfiguration.java`

This task has no dedicated unit test (a one-line bean definition); it is exercised by the application-context test and by `VendorServiceTest` injecting a fixed clock directly. Verification is the boot test in Task 9's full run.

- [ ] **Step 1: Create the configuration**

```java
package com.rapid7.integrationregistry.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Service-layer beans. Supplies the {@link Clock} {@link VendorService} uses for the */
@Configuration
public class ServiceConfiguration {

  /**
   * The clock {@link VendorService} reads when no product contributed a {@code fetched_at} (the
   * all-adapter-failure case), so {@code metadata.as_of} is "as of now, nothing fresh is known".
   * A bean (not {@code Instant.now()}) so tests can inject a fixed clock.
   */
  @Bean
  public Clock registryClock() {
    return Clock.systemUTC();
  }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/service/ServiceConfiguration.java
git commit -m "feat(service): add Clock bean for VendorService as_of computation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `VendorService` shared spine — metadata + unavailable_products + list routes

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/service/VendorService.java`
- Create: `src/test/java/com/rapid7/integrationregistry/service/VendorServiceFixtures.java`
- Test: `src/test/java/com/rapid7/integrationregistry/service/VendorServiceTest.java`

This task builds the spine and the two **list** routes (always-200, simpler). Detail routes and the 404 rule come in Task 8.

- [ ] **Step 1: Create the fixtures helper**

Create `VendorServiceFixtures.java` with builders for the inputs the service consumes. Keep it small and pure.

```java
package com.rapid7.integrationregistry.service;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.coordinator.ProductOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Hand-built fixtures for VendorService tests — no Spring, no Mockito needed to build these. */
final class VendorServiceFixtures {

  private VendorServiceFixtures() {}

  static ProductOutcome served(String product, Instant fetchedAt, boolean cacheHit) {
    return new ProductOutcome.Served(
        product, List.of(anIntegration(product)), fetchedAt, cacheHit, false,
        Optional.empty(), Optional.empty());
  }

  static ProductOutcome staleServed(String product, Instant fetchedAt, Instant staleSince, String reason) {
    return new ProductOutcome.Served(
        product, List.of(anIntegration(product)), fetchedAt, false, true,
        Optional.of(staleSince), Optional.of(reason));
  }

  static ProductOutcome unavailable(String product, String reason) {
    return new ProductOutcome.Unavailable(product, reason);
  }

  static NormalizedIntegration anIntegration(String product) {
    // Build using the real NormalizedIntegration shape. Inspect NormalizedIntegration to match its
    // constructor/builder exactly; this is illustrative of the required fields.
    return NormalizedIntegration.builder()
        .productName(product)
        .sourceIdentifier(new SourceIdentifier("product_type", "microsoft-defender-endpoint"))
        .integrationId("i-1")
        .integrationType("SIEM Event Source")
        .status(IntegrationStatus.HEALTHY)
        .lastSuccessTimestamp(Instant.parse("2026-06-01T00:00:00Z"))
        .configurationUrl("https://example/config")
        .build();
  }
}
```

NOTE: `NormalizedIntegration` may not have a builder — inspect it first (`sed -n '1,120p' src/main/java/com/rapid7/integrationregistry/adapter/NormalizedIntegration.java`) and match its actual constructor. Reuse `aggregator/NormalizedIntegrationFixtures.java` if it already provides what you need rather than duplicating.

- [ ] **Step 2: Write the failing list-route + metadata tests**

Create `VendorServiceTest.java`:

```java
package com.rapid7.integrationregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.aggregator.VendorAggregator;
import com.rapid7.integrationregistry.aggregator.projection.VendorCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard;
import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.HealthState;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import com.rapid7.integrationregistry.coordinator.ProductOutcome;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VendorServiceTest {

  private static final String ORG = "org-1";
  private static final Instant NOW = Instant.parse("2026-06-05T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private FanOutCoordinator coordinator;
  @Mock private VendorAggregator aggregator;

  private VendorService service;

  @BeforeEach
  void setUp() {
    service = new VendorService(coordinator, aggregator, FIXED);
  }

  @Test
  void listVendorServices_asOf_isOldestFetchedAtAcrossServed() {
    Instant older = Instant.parse("2026-06-05T10:00:00Z");
    Instant newer = Instant.parse("2026-06-05T11:00:00Z");
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(
                VendorServiceFixtures.served("InsightConnect", newer, true),
                VendorServiceFixtures.served("InsightIDR", older, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().asOf()).isEqualTo(older);
  }

  @Test
  void cacheHit_trueOnlyWhenEveryProductFreshTierHit() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(
                VendorServiceFixtures.served("InsightConnect", NOW, true),
                VendorServiceFixtures.served("InsightIDR", NOW, false))); // fetched, not fresh hit
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().cacheHit()).isFalse();
  }

  @Test
  void cacheHit_trueWhenAllFreshTierHits() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().cacheHit()).isTrue();
  }

  @Test
  void mappingVersion_isReadFromAggregator() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty())).thenReturn(List.of());
    when(aggregator.mappingVersion()).thenReturn("v9.9.9");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().mappingVersion()).isEqualTo("v9.9.9");
  }

  @Test
  void asOf_fallsBackToClockWhenNoServedOutcomes() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().asOf()).isEqualTo(NOW);
  }

  @Test
  void unavailableProducts_staleServeCarriesStaleTrueAndStaleSinceAndReason() {
    Instant staleSince = Instant.parse("2026-06-04T00:00:00Z");
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(VendorServiceFixtures.staleServed("InsightIDR", NOW, staleSince, "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).hasSize(1);
    var up = resp.unavailableProducts().get(0);
    assertThat(up.stale()).isTrue();
    assertThat(up.staleSince()).isEqualTo(staleSince);
    assertThat(up.reason().wire()).isEqualTo("timeout");
  }

  @Test
  void unavailableProducts_omittedProductCarriesStaleFalseAndReason() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "auth_failure")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).hasSize(1);
    var up = resp.unavailableProducts().get(0);
    assertThat(up.stale()).isFalse();
    assertThat(up.staleSince()).isNull();
    assertThat(up.reason().wire()).isEqualTo("auth_failure");
  }

  @Test
  void unavailableProducts_cleanFreshProductNotListed() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).isEmpty();
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=VendorServiceTest`
Expected: COMPILE FAILURE — `VendorService` does not exist.

- [ ] **Step 4: Implement `VendorService` spine + list routes**

```java
package com.rapid7.integrationregistry.service;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.aggregator.VendorAggregator;
import com.rapid7.integrationregistry.aggregator.projection.VendorCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard;
import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.HealthState;
import com.rapid7.integrationregistry.controller.dto.IntegrationTypeCountDto;
import com.rapid7.integrationregistry.controller.dto.ResponseMetadataDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableProductDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableReason;
import com.rapid7.integrationregistry.controller.dto.VendorListEntryDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceCardDto;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.controller.dto.VendorsResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import com.rapid7.integrationregistry.coordinator.ProductOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Read-path orchestration brain (RFC-001 §Spring layer boundaries — "knows no HTTP"). One entry
 * point per route: delegate to {@link FanOutCoordinator}, unwrap successful {@link
 * ProductOutcome.Served} integrations, hand them to {@link VendorAggregator} with the route's
 * projection, then assemble the Plan-01 response DTO including the shared {@code metadata} block and
 * {@code unavailable_products[]} envelope. Holds the honesty invariants: {@code as_of} is the oldest
 * contributing fetch, {@code cache_hit} requires every product fresh, and 404 is asserted only when
 * fresh and stale both confirm emptiness.
 */
@Service
public class VendorService {

  private final FanOutCoordinator coordinator;
  private final VendorAggregator aggregator;
  private final Clock clock;

  public VendorService(FanOutCoordinator coordinator, VendorAggregator aggregator, Clock clock) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public VendorServicesResponse listVendorServices(String orgId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    List<VendorServiceCard> cards = aggregator.toVendorServiceCards(contributing(outcomes));
    List<VendorServiceCardDto> dtos = new ArrayList<>(cards.size());
    for (VendorServiceCard card : cards) {
      dtos.add(toCardDto(card, metadata.asOf()));
    }
    return new VendorServicesResponse(dtos, unavailable, metadata);
  }

  public VendorsResponse listVendors(String orgId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    List<VendorCard> cards = aggregator.toVendorCards(contributing(outcomes));
    List<VendorListEntryDto> dtos = new ArrayList<>(cards.size());
    for (VendorCard card : cards) {
      dtos.add(new VendorListEntryDto(card.vendorId(), card.vendorName(), card.vendorServicesCount()));
    }
    return new VendorsResponse(dtos, unavailable, metadata);
  }

  // ----- shared spine -----

  /** Flatten every Served outcome's integrations (stale serves still contribute to the grid). */
  private static List<NormalizedIntegration> contributing(List<ProductOutcome> outcomes) {
    List<NormalizedIntegration> all = new ArrayList<>();
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Served served) {
        all.addAll(served.integrations());
      }
    }
    return all;
  }

  private ResponseMetadataDto metadata(List<ProductOutcome> outcomes) {
    boolean cacheHit = !outcomes.isEmpty();
    Instant oldest = null;
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Served served) {
        if (!(served.cacheHitPerProduct() && !served.stale())) {
          cacheHit = false;
        }
        if (oldest == null || served.fetchedAt().isBefore(oldest)) {
          oldest = served.fetchedAt();
        }
      } else {
        cacheHit = false;
      }
    }
    Instant asOf = (oldest != null) ? oldest : clock.instant();
    return new ResponseMetadataDto(cacheHit, asOf, aggregator.mappingVersion());
  }

  private static List<UnavailableProductDto> unavailableProducts(List<ProductOutcome> outcomes) {
    List<UnavailableProductDto> out = new ArrayList<>();
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Unavailable u) {
        out.add(
            new UnavailableProductDto(
                u.productName(), false, reasonOf(u.reason()), null));
      } else if (o instanceof ProductOutcome.Served served && served.stale()) {
        out.add(
            new UnavailableProductDto(
                served.productName(),
                true,
                reasonOf(served.staleReason().orElseThrow()),
                served.staleSince().orElseThrow()));
      }
    }
    return out;
  }

  // ----- translation -----

  static UnavailableReason reasonOf(String wire) {
    for (UnavailableReason r : UnavailableReason.values()) {
      if (r.wire().equals(wire)) {
        return r;
      }
    }
    throw new IllegalStateException("Unknown unavailable reason from coordinator: " + wire);
  }

  static HealthState healthOf(IntegrationStatus status) {
    return switch (status) {
      case HEALTHY -> HealthState.HEALTHY;
      case WARNING -> HealthState.WARNING;
      case ERROR -> HealthState.ERROR;
      case MISSING_DATA -> HealthState.MISSING_DATA;
      case DISABLED -> HealthState.DISABLED;
    };
  }

  private VendorServiceCardDto toCardDto(VendorServiceCard card, Instant asOf) {
    List<IntegrationTypeCountDto> typeCounts = new ArrayList<>(card.integrationTypeCounts().size());
    card.integrationTypeCounts()
        .forEach(c -> typeCounts.add(new IntegrationTypeCountDto(c.integrationType(), c.total(), c.errorCount())));
    return new VendorServiceCardDto(
        card.vendorServiceId(),
        card.vendorServiceName(),
        card.vendorId(),
        card.vendorName(),
        aggregator.wireCategoryOf(card),
        card.integrationsConnected(),
        typeCounts,
        card.productsConnected(),
        healthOf(card.aggregateHealth()),
        card.lastUpdated() != null ? card.lastUpdated() : asOf);
  }
}
```

NOTE: verify `IntegrationTypeCount` projection accessors (`integrationType()`, `total()`, `errorCount()`) against `aggregator/projection/IntegrationTypeCount.java` before relying on them; adjust names if they differ.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=VendorServiceTest`
Expected: PASS (all spine + list-route tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/service/VendorService.java \
        src/test/java/com/rapid7/integrationregistry/service/
git commit -m "feat(service): VendorService spine + list routes

Shared fan-out -> metadata + unavailable_products spine, plus the two
always-200 list routes. as_of = oldest Served fetchedAt (clock fallback
when none); cache_hit true iff every product is a fresh-tier hit.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `VendorService` detail routes + 404-vs-partial rule

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/service/VendorService.java`
- Test: `src/test/java/com/rapid7/integrationregistry/service/VendorServiceTest.java`

- [ ] **Step 1: Write the failing detail-route tests**

Add to `VendorServiceTest.java` (add imports: `VendorServiceDetailResponse`, `VendorDetailResponse`, `VendorServiceDetail`, `VendorScopedView`, `DataSourceDetail`, `Optional`, `java.util.Optional`):

```java
  @Test
  void vendorServiceDetail_notFound_whenAggregatorEmptyAndNoUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceDetail(org.mockito.ArgumentMatchers.eq("nope"),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.Optional.empty());

    java.util.Optional<VendorServiceDetailResponse> resp =
        service.getVendorServiceDetail(ORG, "nope", OutboundAuth.empty());

    assertThat(resp).isEmpty();
  }

  @Test
  void vendorServiceDetail_emptyPayload200_whenAggregatorEmptyButProductUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceDetail(org.mockito.ArgumentMatchers.eq("ms-defender"),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.Optional.empty());

    java.util.Optional<VendorServiceDetailResponse> resp =
        service.getVendorServiceDetail(ORG, "ms-defender", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorServiceDetailResponse body = resp.get();
    assertThat(body.dataSources()).isEmpty();
    assertThat(body.unavailableProducts()).hasSize(1);
    assertThat(body.vendorServiceId()).isEqualTo("ms-defender");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.MISSING_DATA);
    assertThat(body.lastUpdated()).isEqualTo(body.metadata().asOf());
    assertThat(body.vendorId()).isEqualTo("unknown");
  }

  @Test
  void vendorDetail_notFound_whenAggregatorEmptyAndNoUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorScopedView(org.mockito.ArgumentMatchers.eq("nope"),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.Optional.empty());

    java.util.Optional<VendorDetailResponse> resp =
        service.getVendorDetail(ORG, "nope", OutboundAuth.empty());

    assertThat(resp).isEmpty();
  }

  @Test
  void vendorDetail_emptyPayload200_whenAggregatorEmptyButProductUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "upstream_5xx")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorScopedView(org.mockito.ArgumentMatchers.eq("microsoft"),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.Optional.empty());

    java.util.Optional<VendorDetailResponse> resp =
        service.getVendorDetail(ORG, "microsoft", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorDetailResponse body = resp.get();
    assertThat(body.vendorServices()).isEmpty();
    assertThat(body.unavailableProducts()).hasSize(1);
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.MISSING_DATA);
    assertThat(body.lastUpdated()).isEqualTo(body.metadata().asOf());
  }
```

Also add a present-projection test for each detail route. For these, stub the aggregator to return a populated `VendorServiceDetail` / `VendorScopedView` (build via a fixtures helper mirroring the projection records' constructors — inspect them) and assert the DTO carries the mapped fields (e.g. `aggregateHealth` mapped through `healthOf`, `vendorCategory` via `wireCategoryOf`). Keep one happy-path assertion per route.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=VendorServiceTest`
Expected: COMPILE FAILURE — `getVendorServiceDetail` / `getVendorDetail` do not exist.

- [ ] **Step 3: Implement the detail routes**

Add to `VendorService.java` (add imports: `DataSourceDetail`, `IntegrationDetail`, `VendorScopedView`, `VendorServiceDetail` from `aggregator.projection`; `DataSourceDto`, `IntegrationDto`, `VendorDetailResponse`, `VendorServiceCardNestedDto`, `VendorServiceDetailResponse` from `controller.dto`; `java.util.Optional`):

```java
  private static final String UNKNOWN_ID = "unknown";
  private static final String UNKNOWN_NAME = "Unknown";
  private static final String WIRE_CATEGORY_OTHER = "other";

  public Optional<VendorServiceDetailResponse> getVendorServiceDetail(
      String orgId, String vendorServiceId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    Optional<VendorServiceDetail> projection =
        aggregator.toVendorServiceDetail(vendorServiceId, contributing(outcomes));

    if (projection.isPresent()) {
      return Optional.of(toVendorServiceDetailResponse(projection.get(), unavailable, metadata));
    }
    if (unavailable.isEmpty()) {
      return Optional.empty(); // fresh AND stale both confirm emptiness -> 404
    }
    return Optional.of(emptyVendorServiceDetail(vendorServiceId, unavailable, metadata));
  }

  public Optional<VendorDetailResponse> getVendorDetail(
      String orgId, String vendorId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    Optional<VendorScopedView> projection =
        aggregator.toVendorScopedView(vendorId, contributing(outcomes));

    if (projection.isPresent()) {
      return Optional.of(toVendorDetailResponse(projection.get(), unavailable, metadata));
    }
    if (unavailable.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(emptyVendorDetail(vendorId, unavailable, metadata));
  }

  // ----- detail assembly -----

  private VendorServiceDetailResponse toVendorServiceDetailResponse(
      VendorServiceDetail d, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    List<DataSourceDto> dataSources = new ArrayList<>(d.dataSources().size());
    for (DataSourceDetail ds : d.dataSources()) {
      List<IntegrationDto> integrations = new ArrayList<>(ds.integrations().size());
      for (IntegrationDetail in : ds.integrations()) {
        integrations.add(
            new IntegrationDto(
                in.integrationId(),
                in.integrationLabel(),
                healthOf(in.status()),
                in.lastSuccessTimestamp(),
                in.configurationUrl()));
      }
      dataSources.add(
          new DataSourceDto(
              ds.dataSourceId(),
              ds.displayName(),
              ds.integrationType(),
              ds.productName(),
              healthOf(ds.status()),
              ds.integrationsCount(),
              integrations));
    }
    return new VendorServiceDetailResponse(
        d.vendorServiceId(),
        d.vendorServiceName(),
        d.vendorId(),
        d.vendorName(),
        aggregator.wireCategoryOf(d),
        healthOf(d.aggregateHealth()),
        d.lastUpdated() != null ? d.lastUpdated() : metadata.asOf(),
        dataSources,
        unavailable,
        metadata);
  }

  private VendorServiceDetailResponse emptyVendorServiceDetail(
      String vendorServiceId, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    return new VendorServiceDetailResponse(
        vendorServiceId,
        vendorServiceId,
        UNKNOWN_ID,
        UNKNOWN_NAME,
        WIRE_CATEGORY_OTHER,
        HealthState.MISSING_DATA,
        metadata.asOf(),
        List.of(),
        unavailable,
        metadata);
  }

  private VendorDetailResponse toVendorDetailResponse(
      VendorScopedView v, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    List<VendorServiceCardNestedDto> nested = new ArrayList<>(v.vendorServices().size());
    for (VendorServiceCard card : v.vendorServices()) {
      List<IntegrationTypeCountDto> typeCounts =
          new ArrayList<>(card.integrationTypeCounts().size());
      card.integrationTypeCounts()
          .forEach(
              c -> typeCounts.add(new IntegrationTypeCountDto(c.integrationType(), c.total(), c.errorCount())));
      nested.add(
          new VendorServiceCardNestedDto(
              card.vendorServiceId(),
              card.vendorServiceName(),
              aggregator.wireCategoryOf(card),
              card.integrationsConnected(),
              typeCounts,
              card.productsConnected(),
              healthOf(card.aggregateHealth()),
              card.lastUpdated() != null ? card.lastUpdated() : metadata.asOf()));
    }
    return new VendorDetailResponse(
        v.vendorId(),
        v.vendorName(),
        healthOf(v.aggregateHealth()),
        v.lastUpdated() != null ? v.lastUpdated() : metadata.asOf(),
        nested,
        unavailable,
        metadata);
  }

  private VendorDetailResponse emptyVendorDetail(
      String vendorId, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    return new VendorDetailResponse(
        vendorId,
        UNKNOWN_NAME,
        HealthState.MISSING_DATA,
        metadata.asOf(),
        List.of(),
        unavailable,
        metadata);
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=VendorServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/service/VendorService.java \
        src/test/java/com/rapid7/integrationregistry/service/VendorServiceTest.java
git commit -m "feat(service): VendorService detail routes + 404-vs-partial rule

404 (Optional.empty) only when the aggregator finds no match AND
unavailable_products is empty; otherwise a 200 with an empty payload and a
synthesized minimal header (unknown-source convention, MISSING_DATA health,
as_of-backed last_updated).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Spotless format**

Run: `./mvnw -q spotless:apply`
Expected: reformats any files not matching Google Java Format.

- [ ] **Step 2: Full build with all gates (needs Docker for cache tests)**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — all unit tests, ArchUnit (incl. the new auth rule and the unchanged service bans), PMD, and Spotless pass.

- [ ] **Step 3: If anything fails, fix and re-run**

Address compile/test/PMD/ArchUnit failures. Common ones:
- PMD `CouplingBetweenObjects` on `VendorService` (it touches many DTOs by design) — if it fires, add a local `@SuppressWarnings("PMD.CouplingBetweenObjects")` with a justification comment mirroring the precedent on `VendorAggregator`/`FanOutCoordinator`.
- PMD `ExcessiveParameterList` / `ExcessiveMethodLength` on assembly helpers — extract or suppress-with-justification per the project's established pattern.

- [ ] **Step 4: Commit any formatting/suppression cleanup**

```bash
git add -A
git commit -m "chore(service): formatting and PMD suppressions for VendorService

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Four route methods → Tasks 7 (list) + 8 (detail). ✓
- Shared spine (metadata, unavailable_products, contributing flatten) → Task 7. ✓
- `as_of` oldest / clock fallback → Task 7 tests. ✓
- `cache_hit` every-fresh rule → Task 7 tests. ✓
- `mapping_version` via aggregator → Tasks 4 + 7. ✓
- Stale reason on `Served` → Task 1. ✓
- `OutboundAuth` seam + coordinator overload → Tasks 2 + 3. ✓
- Wire-category translation → Task 4. ✓
- 404-vs-partial rule + empty-payload synthesized header → Task 8. ✓
- `IntegrationStatus`→`HealthState` map → Task 7 (`healthOf`). ✓
- `lastUpdated`→`as_of` fallback → Tasks 7 + 8. ✓
- ArchUnit auth-leaf rule + service bans stay green → Tasks 5 + 9. ✓
- Plain JUnit + Mockito + fixed Clock → Tasks 7, 8. ✓

**Placeholder scan:** Two steps intentionally say "inspect the real type before relying on accessor names" (NormalizedIntegration builder, IntegrationTypeCount accessors) — these are verification instructions, not placeholders, because the surrounding code is complete and the instruction is to confirm a name. Acceptable.

**Type consistency:** `reasonOf(String)→UnavailableReason`, `healthOf(IntegrationStatus)→HealthState`, `wireCategoryOf(VendorServiceCard|VendorServiceDetail)→String`, `contributing(List<ProductOutcome>)→List<NormalizedIntegration>`, `metadata(...)→ResponseMetadataDto`, `unavailableProducts(...)→List<UnavailableProductDto>` — names consistent across Tasks 7 and 8. `Served` 7-arg constructor consistent across Tasks 1, 7 fixtures. ✓
