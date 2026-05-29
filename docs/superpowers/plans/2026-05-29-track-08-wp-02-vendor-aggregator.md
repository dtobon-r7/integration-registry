# VendorAggregator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the `VendorAggregator` `@Component` that turns `(List<NormalizedIntegration>, VendorMappingSnapshot)` into the four RFC-001 read-API projection records — full grid, narrow vendor list, vendor-scoped, vendor-service detail — with three-level worst-state-wins rollup, single-row unknown collapse, and per-VS computed aggregates.

**Architecture:** One `@Component` in `com.rapid7.integrationregistry.aggregator` with a single dependency on `VendorMappingSnapshot`. Every public method shares one resolution pass that converts `List<NormalizedIntegration>` to a `List<ResolvedInstance>` (package-private record); per-projection assembly is pure grouping over that list. WARN logging is deduped to once per distinct unmapped triplet per call. `DataSourceIdMinter` gains a public String overload so unmappable enum strings can still be minted into a canonical `data_source_id`.

**Tech Stack:** Java 25 records and pattern switch, Spring Boot 4.0.6 (`@Component` only — no controller/service annotations), JUnit 5, AssertJ, Logback `ListAppender` for log assertions, ArchUnit 1.4.2, PMD 7.17.0, Spotless 3.5.1 + Google Java Format 1.28.0, Maven (wrapper). No new dependencies.

> **Spotless / Google Java Format is now part of `./mvnw verify`** (added in commit `2f8aaba`, `chore: add Spotless with Google Java Format (#8)`). All `.java` source code listed in this plan is **illustrative** — written for human readability with conventional 4-space indent — not byte-exact. After writing each `.java` file, run **`./mvnw -q spotless:apply`** to normalize formatting before running `./mvnw verify`. Spotless is authoritative; do not hand-edit to match GJF output. See `CLAUDE.md` §Quality gates for the full reference.

> **Build environment:** the `maven-enforcer` plugin gates the build on `JAVA_HOME` pointing at JDK 25+. Confirm with `java -version` before Task 1 step 5. JDK 21 / 23 (commonly preinstalled LTS) are insufficient.

**Inputs:**
- Spec: [`docs/superpowers/specs/2026-05-29-track-08-wp-02-vendor-aggregator-design.md`](../specs/2026-05-29-track-08-wp-02-vendor-aggregator-design.md)
- Work plan: [`engagements/.../tracks/08-vendor-aggregator-and-health-rollup/work-plans/02-vendor-aggregator.md`](../../../../../engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/work-plans/02-vendor-aggregator.md)
- Worktree: `.claude/worktrees/track-08-wp-02` on branch `worktree-track-08-wp-02`

**Stop after Task 14.** Do NOT auto-invoke `superpowers:finishing-a-development-branch`. The parent `execute-plan` flow runs Phase 7 (functional review), Phase 8 (simplify), and Phase 9 (external code review) before close-out. The last task explicitly returns control to the parent harness.

---

## File Structure

**Files to create** under `src/main/java/com/rapid7/integrationregistry/aggregator/`:

| File | Responsibility |
|---|---|
| `VendorAggregator.java` | The `@Component`. Four public projection methods + private resolution helper + private grouping helpers. Stateless. |
| `ResolvedInstance.java` | Package-private record `(NormalizedIntegration instance, String dataSourceId, VendorResolution resolution, String displayName)` — the resolution-pass output. |

**Files to modify** under `src/main/java/com/rapid7/integrationregistry/aggregator/`:

| File | Change |
|---|---|
| `DataSourceIdMinter.java` | Add `public static String mint(String, String, String)` overload that validates non-blank `productName`, non-blank/no-`\|` `sourceType`, non-empty/no-`\|` `sourceValue`, then builds the slug. Existing `mint(String, SourceType, String)` delegates: `mint(productName, sourceType.wireForm(), sourceValue)`. Existing tests stay green because the enum overload preserves identical behavior. |

**Files to create** under `src/test/java/com/rapid7/integrationregistry/aggregator/`:

| File | Responsibility |
|---|---|
| `DataSourceIdMinterStringOverloadTest.java` | TDD coverage for the new String overload — the three RFC vectors via String inputs, validation rules (blank `productName`, blank `sourceType`, empty `sourceValue`, `\|` in `sourceType` or `sourceValue`), Turkish-locale stability, and equivalence with the enum overload (parity test). |
| `FakeVendorMappingSnapshot.java` | Hand-rolled `VendorMappingSnapshot` test double. Package-private. Has a `Builder` with `with(String mappingVersion)` factory and a fluent `.map(productName, sourceType, sourceValue, resolution)`. Stores entries in an internal `Map<TripletKey, VendorResolution>` keyed by `(ProductName, SourceType, String)`. Returns `VendorResolution.unknown()` on miss (mirrors `MapBackedVendorMappingSnapshot`). |
| `NormalizedIntegrationFixtures.java` | Static helpers for tests: `idrInstance(integrationId, sourceValue, status)`, `idrInstance(integrationId, sourceValue, status, lastSuccess)`, `iconInstance(integrationId, sourceValue, status)`, `iconInstance(integrationId, sourceValue, status, lastSuccess)`, `instance(productName, sourceType, sourceValue, integrationType, status, integrationId, lastSuccess)` (escape hatch for unmapped/unmappable cases). All return real `NormalizedIntegration` records — no mocks. |
| `VendorAggregatorTest.java` | Primary test class. `@Nested` blocks per scenario family, in this order: `ResolutionTest`, `DataSourceRollupTest`, `VendorServiceRollupTest`, `VendorRollupTest`, `VendorServiceCardsTest`, `VendorCardsTest`, `VendorScopedViewTest`, `VendorServiceDetailTest`, `UnknownCollapseTest`, `EdgeCasesTest`. Logback `ListAppender` set up in `@BeforeEach` / torn down in `@AfterEach`. |

**Commit cadence:** one commit per TDD pair (failing test commit, then passing implementation commit). Use `track-08/wp-02:` prefix, mirroring track-08/wp-01's history (`fb906cf track-05/wp-02: …`, `9daafcd track-08/wp-01: …`). Refactor steps that don't change behavior commit as `refactor(track-08/wp-02): …`.

**Pre-commit formatting:** every task that creates or modifies a `.java` file must run **`./mvnw -q spotless:apply`** immediately before `git add`. Spotless is bound to the `verify` phase, so a missed `:apply` will fail `./mvnw verify` later — but catching it pre-commit keeps the diff clean. The `spotless:apply` step is called out explicitly in every Java-touching task below.

**ArchUnit:** No rule changes. The existing `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping` rule (introduced by Plan 01) already permits the dependencies the aggregator needs (`adapter`, `mapping`); it forbids dependencies on `controller`, `service`, `coordinator`. The `@Component` annotation is on `org.springframework.stereotype` — outside the deny list. `org.slf4j` likewise is not in the deny list. **Verification step in Task 14 confirms the rule passes against the new code, no edit needed.**

**Quality gates that run during `./mvnw verify`** (in order; any failure fails the build):

| Gate | Tool | Failure mode for this plan |
|---|---|---|
| Compile | javac (JDK 25+) | Syntax / type errors in any `.java` |
| Architecture | ArchUnit | New aggregator code touches `controller`/`service`/`coordinator` packages |
| Code quality | PMD 7.17 + `pmd-ruleset.xml` | `GodClass` / `TooManyMethods` / `AvoidDuplicateLiterals` / `MutableStaticState` etc. |
| Formatting | Spotless 3.5 + Google Java Format 1.28 | Any `.java` file not byte-identical to GJF output |
| Tests | JUnit 5 | Any failing test |

---

## Task 1: Pre-flight — read context, confirm green baseline

**Files:** read-only

- [ ] **Step 1: Read the spec end-to-end**

Read: `docs/superpowers/specs/2026-05-29-track-08-wp-02-vendor-aggregator-design.md`

Confirm in your head:
- Four public methods on `VendorAggregator` — two return `List<...>`, two return `Optional<...>`.
- The shared resolution pass produces `ResolvedInstance[]` once per call, then per-projection grouping fans out.
- Unknown collapse: ALL unmapped triplets in a call produce ONE synthetic `VendorServiceCard` (`vendorServiceId="unknown"`, `vendorId="unknown"`, `vendorCategory=OTHER`) with one `DataSourceDetail` per distinct unmapped triplet.
- WARN log fires once per distinct unmapped triplet per call (deduped via `Set<TripletKey>` on a per-call resolution context).
- `displayName` for ALL data sources (mapped and unmapped) is the raw `sourceValue` — the spec's "displayName gap" deferred-scope ruling.

- [ ] **Step 2: Read existing primitives this plan composes**

Read all of these (no edits, just to know the shapes you'll call):

```
src/main/java/com/rapid7/integrationregistry/aggregator/HealthRollup.java
src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java
src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationTypeCount.java
src/main/java/com/rapid7/integrationregistry/aggregator/VendorCard.java
src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceCard.java
src/main/java/com/rapid7/integrationregistry/aggregator/VendorScopedView.java
src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceDetail.java
src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceDetail.java
src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationDetail.java
src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java
src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java
src/main/java/com/rapid7/integrationregistry/mapping/ProductName.java
src/main/java/com/rapid7/integrationregistry/mapping/SourceType.java
src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java
src/main/java/com/rapid7/integrationregistry/adapter/NormalizedIntegration.java
src/main/java/com/rapid7/integrationregistry/adapter/SourceIdentifier.java
src/main/java/com/rapid7/integrationregistry/adapter/IntegrationStatus.java
```

Note specifically:
- `NormalizedIntegration.productName` is `String` (not `ProductName` enum). Use `ProductName.fromWireForm(...)` to convert; it returns `Optional<ProductName>`.
- `SourceIdentifier.sourceType` is `String`. Use `SourceType.fromWireForm(...)`; same `Optional` return.
- `VendorResolution.unknown()` returns the canned synthetic identity (`"unknown"` ids, `OTHER` category) — use `Objects.equals(resolution, VendorResolution.unknown())` to detect the snapshot's miss path.
- `HealthRollup.worstOf(IntegrationStatus, IntegrationStatus)` is the binary precedence reducer. Reduce a stream with `.reduce(HealthRollup::worstOf).orElseThrow()`.
- `DataSourceIdMinter.mint(String, SourceType, String)` requires non-blank `productName`, non-empty `sourceValue`, and rejects `\|` in `sourceValue`. Constants: `DELIMITER = '\|'`.

- [ ] **Step 3: Read existing test conventions in this repo**

Read: `src/test/java/com/rapid7/integrationregistry/aggregator/HealthRollupTest.java`
Read: `src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterTest.java`
Read: `src/test/java/com/rapid7/integrationregistry/aggregator/ProjectionRecordsTest.java`

Note specifically:
- `@Nested` for scenario families.
- `// Arrange / // Act / // Assert` blocks inside each `@Test`.
- Method naming: `methodName_shouldDoX_whenY` (e.g. `mint_shouldThrowNPE_whenSourceValueNull`).
- AssertJ helpers: `assertThatNullPointerException()`, `assertThatIllegalArgumentException()`, `assertThat(...)`.
- `static final` test constants at the top of nested classes.

- [ ] **Step 4: Read the existing ArchUnit rule that gates the aggregator**

Read: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`

Lines 32-35 are the `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping` rule. The new code MUST stay within `..adapter..` and `..mapping..` (and packages outside the registry, like `org.springframework.stereotype` and `org.slf4j` and `java.*`). It MUST NOT pull from `..controller..`, `..service..`, or `..coordinator..`.

- [ ] **Step 5: Verify baseline `./mvnw verify` is green**

Run: `java -version` first — if it doesn't say `25.x` or higher, stop and switch JDK before continuing (`maven-enforcer` will fail otherwise).

Then run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. The output should include a `spotless` lifecycle step (Spotless 3.5.1 was added in commit `2f8aaba`); if it's missing, the rebase from origin/main never landed and the plan's Spotless instructions won't apply — stop and ask the user.

- [ ] **Step 6: No commit for Task 1** — read-only.

---

## Task 2: DataSourceIdMinter String overload — failing test

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterStringOverloadTest.java`

- [ ] **Step 1: Write the failing test**

Create the test file. Covers: the three RFC vectors via String inputs, every validation rule on the raw `sourceType` String, the parity assertion that the enum overload's output matches the String overload, and Turkish-locale stability.

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DataSourceIdMinterStringOverloadTest {

    @Test
    void mintString_shouldProduceCanonicalId_forInsightIdrProductTypeVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "InsightIDR", "product_type", "microsoft-defender-endpoint");

        // Assert
        assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
    }

    @Test
    void mintString_shouldProduceCanonicalId_forInsightConnectPluginNameVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "InsightConnect", "plugin_name", "microsoft-defender");

        // Assert
        assertThat(result).isEqualTo("insightconnect|plugin_name|microsoft-defender");
    }

    @Test
    void mintString_shouldProduceCanonicalId_forSurfaceCommandIntegrationIdVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "Surface Command", "integration_id", "com.rapid7.microsoft-defender-for-endpoint");

        // Assert
        assertThat(result).isEqualTo(
            "surface-command|integration_id|com.rapid7.microsoft-defender-for-endpoint");
    }

    @Test
    void mintString_shouldProduceSameResultAsEnumOverload_forEverySourceType() {
        // Arrange — parity guard: the enum overload must delegate to the String one
        // and produce identical output.
        for (SourceType sourceType : SourceType.values()) {
            // Act
            String viaEnum = DataSourceIdMinter.mint("InsightIDR", sourceType, "value-x");
            String viaString = DataSourceIdMinter.mint("InsightIDR", sourceType.wireForm(), "value-x");

            // Assert
            assertThat(viaString).as("mint via String must match mint via SourceType enum for %s", sourceType)
                .isEqualTo(viaEnum);
        }
    }

    @Test
    void mintString_shouldProduceCanonicalId_evenWhenDefaultLocaleIsTurkish() {
        // Arrange — Turkish locale lowercases 'I' to 'ı' by default; the minter
        // MUST use Locale.ROOT for the productName slug. Already true for the
        // enum overload; the parity guard ensures the String overload preserves it.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            // Act
            String result = DataSourceIdMinter.mint(
                "InsightIDR", "product_type", "microsoft-defender-endpoint");

            // Assert
            assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void mintString_shouldThrowNPE_whenProductNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint(null, "product_type", "x"))
            .withMessage("productName");
    }

    @Test
    void mintString_shouldThrowNPE_whenSourceTypeNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", (String) null, "x"))
            .withMessage("sourceType");
    }

    @Test
    void mintString_shouldThrowNPE_whenSourceValueNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", null))
            .withMessage("sourceValue");
    }

    @Test
    void mintString_shouldThrowIAE_whenProductNameBlank() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("   ", "product_type", "x"))
            .withMessageContaining("productName")
            .withMessageContaining("blank");
    }

    @Test
    void mintString_shouldThrowIAE_whenSourceTypeBlank() {
        // Arrange — whitespace-only sourceType is treated like an empty enum miss:
        // not a degradation path, a programming error.
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", "   ", "x"))
            .withMessageContaining("sourceType")
            .withMessageContaining("blank");
    }

    @Test
    void mintString_shouldThrowIAE_whenSourceTypeContainsDelimiter() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", "a|b", "x"))
            .withMessageContaining("sourceType")
            .withMessageContaining("|");
    }

    @Test
    void mintString_shouldThrowIAE_whenSourceValueEmpty() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", ""))
            .withMessageContaining("sourceValue")
            .withMessageContaining("empty");
    }

    @Test
    void mintString_shouldThrowIAE_whenSourceValueContainsDelimiter() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", "a|b"))
            .withMessageContaining("sourceValue")
            .withMessageContaining("|");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=DataSourceIdMinterStringOverloadTest`
Expected: COMPILATION FAILURE. The String overload `mint(String, String, String)` does not exist yet.

- [ ] **Step 3: Format with Spotless**

Run: `./mvnw -q spotless:apply`
Expected: no errors. Spotless rewrites the test file to GJF style (2-space indent, static imports above non-static, ~100-col wrap). Re-running it after the rewrite is a no-op.

- [ ] **Step 4: Commit the failing test**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterStringOverloadTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-02): failing tests for DataSourceIdMinter String overload

Adds the TDD red half for the String-typed mint overload that the
VendorAggregator needs when ProductName/SourceType wire-form lookups
return Optional.empty(). Covers RFC vectors via String inputs, parity
with the enum overload, Turkish-locale stability, and the new
non-blank/no-pipe validation on the raw sourceType string.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: DataSourceIdMinter String overload — implementation

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java`

- [ ] **Step 1: Replace the file with the dual-overload implementation**

The existing file already has the slug-build logic and the constants. Replace its body so that `mint(String, String, String)` is the canonical implementation and the enum overload delegates.

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;

import java.util.Locale;
import java.util.Objects;

/**
 * Mints the canonical {@code data_source_id} per RFC-001 §Data Model →
 * {@code data_source_id} construction:
 *
 * <pre>data_source_id = lower(productName).replace(' ', '-')
 *                  + '|' + sourceType
 *                  + '|' + sourceValue</pre>
 *
 * <p>Two overloads share the same formula. The enum overload is the preferred
 * call site (type-safe sourceType); the String overload exists for the
 * aggregator's unmappable-enum fallback path, where {@code SourceType.fromWireForm}
 * returned {@code Optional.empty()} and a raw wire-form string is all we have.
 */
public final class DataSourceIdMinter {

    private static final char DELIMITER = '|';
    private static final String FIELD_PRODUCT_NAME = "productName";
    private static final String FIELD_SOURCE_TYPE = "sourceType";
    private static final String FIELD_SOURCE_VALUE = "sourceValue";

    private DataSourceIdMinter() {}

    /**
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code productName} or {@code sourceType}
     *     is blank, if {@code sourceValue} is empty, or if {@code sourceType} or
     *     {@code sourceValue} contains '|' (which would make the composite
     *     ambiguously parseable)
     */
    public static String mint(String productName, String sourceType, String sourceValue) {
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
        Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
        if (productName.isBlank()) {
            throw new IllegalArgumentException(FIELD_PRODUCT_NAME + " must not be blank");
        }
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException(FIELD_SOURCE_TYPE + " must not be blank");
        }
        if (sourceType.indexOf(DELIMITER) >= 0) {
            throw new IllegalArgumentException(
                FIELD_SOURCE_TYPE + " must not contain '|': " + sourceType);
        }
        if (sourceValue.isEmpty()) {
            throw new IllegalArgumentException(FIELD_SOURCE_VALUE + " must not be empty");
        }
        if (sourceValue.indexOf(DELIMITER) >= 0) {
            throw new IllegalArgumentException(
                FIELD_SOURCE_VALUE + " must not contain '|': " + sourceValue);
        }
        String slug = productName.toLowerCase(Locale.ROOT).replace(' ', '-');
        return slug + DELIMITER + sourceType + DELIMITER + sourceValue;
    }

    /**
     * Type-safe overload that delegates to {@link #mint(String, String, String)}
     * with {@code sourceType.wireForm()}. Behaviour is identical for in-enum
     * source types — existing call sites and tests are unaffected.
     */
    public static String mint(String productName, SourceType sourceType, String sourceValue) {
        Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
        return mint(productName, sourceType.wireForm(), sourceValue);
    }
}
```

Two notes for the implementing agent:
- The pre-NPE on `sourceType` in the enum overload is needed because the delegated `mint(String, String, String)` would otherwise throw NPE on `sourceType.wireForm()` with a non-fielded message (`Cannot invoke ... wireForm() because sourceType is null`); preserving the existing `"sourceType"` NPE message keeps `DataSourceIdMinterTest.mint_shouldThrowNPE_whenSourceTypeNull` green.
- Validation order of `sourceValue` matters: empty check before the `|` check (the empty-string `indexOf('|')` returns `-1`, so the order is mostly aesthetic — but the existing test `mint_shouldThrowIAE_whenSourceValueEmpty` asserts the message contains "empty", so the empty check must fire first for empty input).

- [ ] **Step 2: Run the new test class**

Run: `./mvnw -q test -Dtest=DataSourceIdMinterStringOverloadTest`
Expected: PASS, all twelve tests green.

- [ ] **Step 3: Run the existing minter test class**

Run: `./mvnw -q test -Dtest=DataSourceIdMinterTest`
Expected: PASS, no regressions. (Validates the enum overload's behavior is preserved by the delegation.)

- [ ] **Step 4: Format with Spotless**

Run: `./mvnw -q spotless:apply`
Expected: rewrites the file to GJF style (the existing file in the repo is already GJF-formatted; Spotless reformats your edits to match).

- [ ] **Step 5: Run full `./mvnw verify`**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. PMD must be green — `AvoidDuplicateLiterals` could trip on the field-name strings, but the file uses `static final` constants for them, so it's fine. Spotless `:check` (bound to `verify`) passes because Step 4 just formatted the file.

- [ ] **Step 6: Commit the implementation**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): DataSourceIdMinter String overload

Adds public mint(String, String, String) overload. The existing
enum-typed mint(String, SourceType, String) now delegates so behavior
is unchanged for in-enum source types. The String overload validates
non-blank productName, non-blank/no-pipe sourceType, non-empty/no-pipe
sourceValue — same rules as the enum overload, applied to the raw
wire-form string.

This is the foundation the VendorAggregator's resolution helper needs
when SourceType.fromWireForm returns Optional.empty() — the aggregator
must still mint a canonical data_source_id for the unmapped-triplet
collapse path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ResolvedInstance record + test scaffolding (FakeVendorMappingSnapshot, NormalizedIntegrationFixtures)

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/ResolvedInstance.java`
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/FakeVendorMappingSnapshot.java`
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/NormalizedIntegrationFixtures.java`

This task lands the production record + the two test-side helpers as one unit because they exist to support each other and have no behavior to TDD on their own (the record carries no logic; the fake is straight key-lookup; the fixtures are constructors). They become the substrate every later test uses.

- [ ] **Step 1: Create `ResolvedInstance`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.VendorResolution;

import java.util.Objects;

/**
 * Output of {@code VendorAggregator}'s shared resolution pass — one record per
 * input {@link NormalizedIntegration}, carrying the minted {@code dataSourceId},
 * the canonical {@link VendorResolution} (real or {@link VendorResolution#unknown()}),
 * and the per-data-source {@code displayName}.
 *
 * <p>Per the spec's {@code displayName} gap: {@code displayName} is the raw
 * {@code sourceValue} for both mapped and unmapped triplets until the snapshot
 * surfaces curated bundle {@code display_name} values.
 *
 * <p>Package-private — internal contract between resolution and projection
 * stages of {@code VendorAggregator}, never surfaced on the public API.
 */
record ResolvedInstance(
    NormalizedIntegration instance,
    String dataSourceId,
    VendorResolution resolution,
    String displayName
) {

    static final String FIELD_INSTANCE = "instance";
    static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
    static final String FIELD_RESOLUTION = "resolution";
    static final String FIELD_DISPLAY_NAME = "displayName";

    ResolvedInstance {
        Objects.requireNonNull(instance, FIELD_INSTANCE);
        Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
        Objects.requireNonNull(resolution, FIELD_RESOLUTION);
        Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
    }
}
```

- [ ] **Step 2: Create `FakeVendorMappingSnapshot`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-rolled in-memory {@link VendorMappingSnapshot} for tests. Mirrors
 * production behavior: returns {@link VendorResolution#unknown()} on miss,
 * never null, never throws on unmapped input.
 *
 * <p>Builder-style construction keeps test arrangement readable:
 *
 * <pre>{@code
 * VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot
 *     .with("v1.42.0")
 *     .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
 *          "microsoft-defender-endpoint",
 *          new VendorResolution("microsoft-defender", "Microsoft Defender",
 *              VendorCategory.EDR, "microsoft", "Microsoft"))
 *     .build();
 * }</pre>
 */
final class FakeVendorMappingSnapshot implements VendorMappingSnapshot {

    private record TripletKey(ProductName productName, SourceType sourceType, String sourceValue) {
        private TripletKey {
            Objects.requireNonNull(productName, "productName");
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(sourceValue, "sourceValue");
        }
    }

    private final Map<TripletKey, VendorResolution> index;
    private final String mappingVersion;

    private FakeVendorMappingSnapshot(Map<TripletKey, VendorResolution> index, String mappingVersion) {
        this.index = Map.copyOf(index);
        this.mappingVersion = mappingVersion;
    }

    static Builder with(String mappingVersion) {
        return new Builder(mappingVersion);
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        return index.getOrDefault(
            new TripletKey(productName, sourceType, sourceValue),
            VendorResolution.unknown());
    }

    @Override
    public String mappingVersion() {
        return mappingVersion;
    }

    static final class Builder {
        private final Map<TripletKey, VendorResolution> index = new HashMap<>();
        private final String mappingVersion;

        private Builder(String mappingVersion) {
            this.mappingVersion = Objects.requireNonNull(mappingVersion, "mappingVersion");
        }

        Builder map(ProductName productName, SourceType sourceType,
                    String sourceValue, VendorResolution resolution) {
            Objects.requireNonNull(resolution, "resolution");
            index.put(new TripletKey(productName, sourceType, sourceValue), resolution);
            return this;
        }

        FakeVendorMappingSnapshot build() {
            return new FakeVendorMappingSnapshot(index, mappingVersion);
        }
    }
}
```

- [ ] **Step 3: Create `NormalizedIntegrationFixtures`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;

import java.time.Instant;

/**
 * Static helpers for building {@link NormalizedIntegration} test fixtures —
 * keeps each test scenario readable by collapsing the 9-arg constructor down
 * to a 3- or 4-arg call. Real records, no mocks.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code productName} / {@code integrationType} / {@code sourceType}
 *       use the canonical wire-form strings the adapters write
 *       (see RFC-001 §Canonical productName values, §source_type enum,
 *       §Integration Types).
 *   <li>{@code customerAccountId} is fixed to {@code "test-org"} — the
 *       aggregator does not consume it; tests do not care.
 *   <li>{@code configurationUrl} is fixed per product. The aggregator does
 *       not consume it for grouping; tests do not care.
 *   <li>{@code lastSuccess} of {@code null} is supported (the record
 *       permits it).
 * </ul>
 */
final class NormalizedIntegrationFixtures {

    static final String CUSTOMER_ACCOUNT_ID = "test-org";

    private NormalizedIntegrationFixtures() {}

    static NormalizedIntegration idrInstance(String integrationId, String sourceValue,
                                             IntegrationStatus status) {
        return idrInstance(integrationId, sourceValue, status, null);
    }

    static NormalizedIntegration idrInstance(String integrationId, String sourceValue,
                                             IntegrationStatus status, Instant lastSuccess) {
        return new NormalizedIntegration(
            integrationId,
            new SourceIdentifier("product_type", sourceValue),
            "InsightIDR",
            "SIEM Event Source",
            "idr-" + integrationId,
            status,
            lastSuccess,
            "https://idr.example/eventsources/" + integrationId,
            CUSTOMER_ACCOUNT_ID);
    }

    static NormalizedIntegration iconInstance(String integrationId, String sourceValue,
                                              IntegrationStatus status) {
        return iconInstance(integrationId, sourceValue, status, null);
    }

    static NormalizedIntegration iconInstance(String integrationId, String sourceValue,
                                              IntegrationStatus status, Instant lastSuccess) {
        return new NormalizedIntegration(
            integrationId,
            new SourceIdentifier("plugin_name", sourceValue),
            "InsightConnect",
            "Automation Plugin",
            null,                                       // ICON exposes no per-instance label
            status,
            lastSuccess,
            "https://icon.example/connections/" + integrationId,
            CUSTOMER_ACCOUNT_ID);
    }

    /** Escape hatch for unmapped / unmappable / cross-type scenarios. */
    static NormalizedIntegration instance(String productName, String sourceType, String sourceValue,
                                          String integrationType, IntegrationStatus status,
                                          String integrationId, Instant lastSuccess) {
        return new NormalizedIntegration(
            integrationId,
            new SourceIdentifier(sourceType, sourceValue),
            productName,
            integrationType,
            null,
            status,
            lastSuccess,
            "https://example/" + integrationId,
            CUSTOMER_ACCOUNT_ID);
    }
}
```

- [ ] **Step 4: Format with Spotless**

Run: `./mvnw -q spotless:apply`
Expected: rewrites the three new files to GJF style.

- [ ] **Step 5: Build, do not yet run aggregator tests (none exist)**

Run: `./mvnw -q test-compile`
Expected: SUCCESS. (No `VendorAggregatorTest` yet, so `./mvnw test` would just be a no-op for our scope.)

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. ArchUnit must be green:
- `ResolvedInstance` (production code) sits in `aggregator` and depends on `adapter` (`NormalizedIntegration`) and `mapping` (`VendorResolution`) — both allowed by `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping`.
- `FakeVendorMappingSnapshot` and `NormalizedIntegrationFixtures` are test-source files, outside the production-class set ArchUnit scans (the existing `LayerDependencyRulesTest` only imports production classes). PMD does scan test code; the new files are simple enough to stay under all complexity rules.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/ResolvedInstance.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/FakeVendorMappingSnapshot.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/NormalizedIntegrationFixtures.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): ResolvedInstance record + aggregator test scaffolding

Lands three units that have no behavior to TDD on their own:
- ResolvedInstance: package-private record carrying the resolution-pass
  output (instance, dataSourceId, resolution, displayName). Internal
  contract between VendorAggregator's resolution and projection stages.
- FakeVendorMappingSnapshot: hand-rolled VendorMappingSnapshot test
  double with a Builder. Mirrors MapBackedVendorMappingSnapshot's
  miss-returns-unknown semantics. No Mockito.
- NormalizedIntegrationFixtures: idrInstance / iconInstance / instance
  static helpers so test scenarios read as scenarios, not setup walls.

Substrate for the VendorAggregator TDD cycles that follow.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: VendorAggregator scaffold + first known-triplet ResolutionTest

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

This task plants the scaffold for `VendorAggregator` plus the first end-to-end test through `toVendorServiceCards` for a single known-triplet input. Once green, the rest of the plan layers more scenarios into the same test class with TDD cycles.

- [ ] **Step 1: Write the failing test**

Create `VendorAggregatorTest.java` with the Logback `ListAppender` setup and one nested `ResolutionTest` block carrying the first scenario.

```java
package com.rapid7.integrationregistry.aggregator;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.rapid7.integrationregistry.aggregator.NormalizedIntegrationFixtures.idrInstance;
import static org.assertj.core.api.Assertions.assertThat;

class VendorAggregatorTest {

    static final String MAPPING_VERSION = "v1.42.0";

    static final VendorResolution MS_DEFENDER = new VendorResolution(
        "microsoft-defender", "Microsoft Defender", VendorCategory.EDR,
        "microsoft", "Microsoft");

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(VendorAggregator.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(VendorAggregator.class)).detachAppender(appender);
    }

    private VendorAggregator aggregatorWith(VendorMappingSnapshot snapshot) {
        return new VendorAggregator(snapshot);
    }

    @Nested
    class ResolutionTest {

        @Test
        void toVendorServiceCards_shouldEmitOneCard_forSingleKnownTriplet() {
            // Arrange — one IDR instance for Microsoft Defender. The snapshot has
            // exactly the triplet mapped.
            VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
                .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                     "microsoft-defender-endpoint", MS_DEFENDER)
                .build();
            NormalizedIntegration instance = idrInstance(
                "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY);

            // Act
            List<VendorServiceCard> cards = aggregatorWith(snapshot)
                .toVendorServiceCards(List.of(instance));

            // Assert — single card carrying the resolved identity, one instance, no WARN
            assertThat(cards).hasSize(1);
            VendorServiceCard card = cards.get(0);
            assertThat(card.vendorServiceId()).isEqualTo("microsoft-defender");
            assertThat(card.vendorServiceName()).isEqualTo("Microsoft Defender");
            assertThat(card.vendorId()).isEqualTo("microsoft");
            assertThat(card.vendorName()).isEqualTo("Microsoft");
            assertThat(card.vendorCategory()).isEqualTo(VendorCategory.EDR);
            assertThat(card.integrationsConnected()).isEqualTo(1);
            assertThat(card.aggregateHealth()).isEqualTo(IntegrationStatus.HEALTHY);
            assertThat(appender.list).isEmpty();
        }
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: COMPILATION FAILURE. `VendorAggregator` does not exist.

- [ ] **Step 3: Create the `VendorAggregator` scaffold + minimum implementation**

Create `VendorAggregator.java` with the structure that supports this scenario AND the entire shape of the plan (so later tasks add code, not redesign). The implementation here resolves, mints, and assembles a single card. Unknown handling, projections beyond `toVendorServiceCards`, and rollup details land in later tasks — but the resolution helper and the per-call context already account for them.

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stateless component that turns a flat list of {@link NormalizedIntegration}
 * (from the fan-out coordinator) plus the {@link VendorMappingSnapshot} into
 * the four read-API projection records — vendor-service cards, vendor cards,
 * vendor-scoped views, and vendor-service details. See RFC-001 §Component
 * Design → VendorAggregator.
 *
 * <p>Every public method runs the same private resolution pass first, then
 * fans out into projection-specific grouping. Resolution is a single-pass
 * walk that converts each instance's raw {@code (productName, sourceType,
 * sourceValue)} triplet into a {@link ResolvedInstance} carrying the minted
 * {@code data_source_id}, the canonical {@link VendorResolution}, and the
 * per-data-source {@code displayName}. Unmapped triplets — including those
 * where the raw strings don't even resolve to {@link ProductName} or
 * {@link SourceType} enum members — fold into {@link VendorResolution#unknown()}
 * and emit a single WARN per distinct triplet per call.
 */
@Component
public final class VendorAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(VendorAggregator.class);

    private static final String UNMAPPED_LOG_FORMAT =
        "Unmapped vendor mapping triplet: productName='{}' sourceType='{}' "
            + "sourceValue='{}' mappingVersion='{}'";

    private final VendorMappingSnapshot snapshot;

    public VendorAggregator(VendorMappingSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public List<VendorServiceCard> toVendorServiceCards(List<NormalizedIntegration> instances) {
        Objects.requireNonNull(instances, "instances");
        if (instances.isEmpty()) {
            return List.of();
        }
        List<ResolvedInstance> resolved = resolveAll(instances);
        return buildVendorServiceCards(resolved);
    }

    public List<VendorCard> toVendorCards(List<NormalizedIntegration> instances) {
        Objects.requireNonNull(instances, "instances");
        // Implemented in a later task. Returning an empty list keeps the public
        // surface compilable for tests that only exercise toVendorServiceCards.
        if (instances.isEmpty()) {
            return List.of();
        }
        return List.of();
    }

    public Optional<VendorScopedView> toVendorScopedView(
            String vendorId, List<NormalizedIntegration> instances) {
        Objects.requireNonNull(vendorId, "vendorId");
        Objects.requireNonNull(instances, "instances");
        // Implemented in a later task.
        return Optional.empty();
    }

    public Optional<VendorServiceDetail> toVendorServiceDetail(
            String vendorServiceId, List<NormalizedIntegration> instances) {
        Objects.requireNonNull(vendorServiceId, "vendorServiceId");
        Objects.requireNonNull(instances, "instances");
        // Implemented in a later task.
        return Optional.empty();
    }

    // ----- resolution pass -----

    private record TripletKey(String productName, String sourceType, String sourceValue) {}

    private List<ResolvedInstance> resolveAll(List<NormalizedIntegration> instances) {
        Set<TripletKey> warned = new HashSet<>();
        List<ResolvedInstance> resolved = new ArrayList<>(instances.size());
        for (NormalizedIntegration n : instances) {
            Objects.requireNonNull(n, "instance");
            resolved.add(resolveOne(n, warned));
        }
        return resolved;
    }

    private ResolvedInstance resolveOne(NormalizedIntegration n, Set<TripletKey> warned) {
        String rawProductName = n.productName();
        String rawSourceType = n.sourceIdentifier().sourceType();
        String sourceValue = n.sourceIdentifier().sourceValue();

        Optional<ProductName> productEnum = ProductName.fromWireForm(rawProductName);
        Optional<SourceType> sourceTypeEnum = SourceType.fromWireForm(rawSourceType);

        if (productEnum.isPresent() && sourceTypeEnum.isPresent()) {
            VendorResolution resolution = snapshot.lookup(
                productEnum.get(), sourceTypeEnum.get(), sourceValue);
            String dataSourceId = DataSourceIdMinter.mint(
                rawProductName, sourceTypeEnum.get(), sourceValue);
            if (Objects.equals(resolution, VendorResolution.unknown())) {
                warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
            }
            return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
        }

        // Unmappable enum strings — route through the same unknown path.
        VendorResolution resolution = VendorResolution.unknown();
        String dataSourceId = DataSourceIdMinter.mint(rawProductName, rawSourceType, sourceValue);
        warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
        return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
    }

    private void warnOnceForTriplet(String productName, String sourceType, String sourceValue,
                                    Set<TripletKey> warned) {
        if (warned.add(new TripletKey(productName, sourceType, sourceValue))) {
            LOG.warn(UNMAPPED_LOG_FORMAT, productName, sourceType, sourceValue,
                snapshot.mappingVersion());
        }
    }

    // ----- vendor-service cards -----

    private List<VendorServiceCard> buildVendorServiceCards(List<ResolvedInstance> resolved) {
        Map<String, List<ResolvedInstance>> byVendorServiceId = new LinkedHashMap<>();
        for (ResolvedInstance r : resolved) {
            byVendorServiceId
                .computeIfAbsent(r.resolution().vendorServiceId(), k -> new ArrayList<>())
                .add(r);
        }
        List<VendorServiceCard> cards = new ArrayList<>(byVendorServiceId.size());
        for (Map.Entry<String, List<ResolvedInstance>> e : byVendorServiceId.entrySet()) {
            cards.add(buildVendorServiceCard(e.getValue()));
        }
        return cards;
    }

    private VendorServiceCard buildVendorServiceCard(List<ResolvedInstance> group) {
        ResolvedInstance first = group.get(0);
        VendorResolution r = first.resolution();
        // Aggregates and rollup land in later tasks. For now: simple instance
        // count, healthy-by-default rollup is wrong but is replaced before it
        // ships to PR — Task 7's mixed-state DS test forces this code path to
        // use HealthRollup correctly.
        int integrationsConnected = group.size();
        return new VendorServiceCard(
            r.vendorServiceId(), r.vendorServiceName(), r.vendorId(), r.vendorName(),
            r.vendorCategory(),
            integrationsConnected,
            List.of(),                                         // integrationTypeCounts — Task 9
            List.of(),                                         // productsConnected — Task 9
            group.get(0).instance().status(),                  // aggregateHealth — Task 7 makes this correct
            null);                                             // lastUpdated — Task 9
    }
}
```

The placeholders inside `buildVendorServiceCard` are deliberate — they make the first test pass and keep the public method shape stable while later TDD cycles drive the correct rollup, type-counts, and `lastUpdated`. Each placeholder has a Task that owns it.

- [ ] **Step 4: Run test, verify it passes**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — the single scenario goes green.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`
Expected: rewrites both new files to GJF style.

- [ ] **Step 6: Run full `./mvnw verify`**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. ArchUnit:
- `org.springframework.stereotype.Component` — outside the deny list (`controller`, `service`, `coordinator`, `..mapping..` is allowed).
- `org.slf4j.Logger` / `LoggerFactory` — outside the deny list.

PMD:
- `MutableStaticState` does not fire — `LOG` is `static final` and a `Logger` is not "mutable static state" in the PMD-defined sense; the rule targets non-final mutable references like collections.
- `AvoidDuplicateLiterals` — the `"instances"` and `"snapshot"` strings appear several times; they are NPE field names, an established pattern in the existing records (`Objects.requireNonNull(..., "fieldName")`). The rule fires on literals appearing 4+ times by default; we have at most 3. If PMD fires, extract a `private static final String FIELD_INSTANCES = "instances";` etc.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): VendorAggregator scaffold + first happy-path test

Plants the @Component with all four projection methods on the public
surface (three are deliberate stubs that later tasks fill in, so the
shape stays stable). Resolution helper + WARN-dedup TripletKey ride
along now since they are needed by the very first test scenario.

VendorAggregatorTest sets up the Logback ListAppender in @BeforeEach /
detaches in @AfterEach. The first @Nested ResolutionTest scenario:
single IDR instance for Microsoft Defender, snapshot has the triplet,
expects one VendorServiceCard with the resolved identity and zero WARN
events.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Resolution — unmapped triplet + unmappable enum strings — failing tests

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

This task adds three failing scenarios to `ResolutionTest`. They drive both the unmapped-triplet path AND the unmappable-enum-string path — but the implementation already handles them (Task 5 wired the `else` branch). The TDD discipline here is to *prove* the behavior with assertions before claiming the rollup paths work, because Task 5's `buildVendorServiceCard` shortcuts on `group.get(0).instance().status()` — that's still correct for these single-instance scenarios but we need the assertions to lock the contract before later tasks touch the rollup logic.

- [ ] **Step 1: Add three scenarios to `ResolutionTest`**

Append inside the `ResolutionTest` `@Nested` class:

```java
@Test
void toVendorServiceCards_shouldEmitSyntheticCard_whenSnapshotReturnsUnknown() {
    // Arrange — one ICON instance, snapshot has nothing mapped at all
    VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
    NormalizedIntegration instance = NormalizedIntegrationFixtures.iconInstance(
        "c_1", "new-product-x", IntegrationStatus.HEALTHY);

    // Act
    List<VendorServiceCard> cards = aggregatorWith(snapshot)
        .toVendorServiceCards(List.of(instance));

    // Assert — synthetic VS card with the canonical unknown identity
    assertThat(cards).hasSize(1);
    VendorServiceCard card = cards.get(0);
    assertThat(card.vendorServiceId()).isEqualTo("unknown");
    assertThat(card.vendorServiceName()).isEqualTo("Unknown");
    assertThat(card.vendorId()).isEqualTo("unknown");
    assertThat(card.vendorName()).isEqualTo("Unknown");
    assertThat(card.vendorCategory()).isEqualTo(VendorCategory.OTHER);
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("InsightConnect")
        .contains("plugin_name")
        .contains("new-product-x")
        .contains(MAPPING_VERSION);
}

@Test
void toVendorServiceCards_shouldRouteToUnknownPath_whenProductNameStringIsUnmappable() {
    // Arrange — adapter wrote a productName string that isn't a ProductName enum value.
    // Spec ruling Q1.1: fold into unknown-collapse path with WARN.
    VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
    NormalizedIntegration instance = NormalizedIntegrationFixtures.instance(
        "MysteryProduct", "product_type", "weird-source",
        "Unknown Type", IntegrationStatus.WARNING, "i_1", null);

    // Act
    List<VendorServiceCard> cards = aggregatorWith(snapshot)
        .toVendorServiceCards(List.of(instance));

    // Assert — synthetic card; WARN content carries the raw values
    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).vendorServiceId()).isEqualTo("unknown");
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("MysteryProduct")
        .contains("product_type")
        .contains("weird-source")
        .contains(MAPPING_VERSION);
}

@Test
void toVendorServiceCards_shouldRouteToUnknownPath_whenSourceTypeStringIsUnmappable() {
    // Arrange — productName is canonical, sourceType is junk.
    VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
    NormalizedIntegration instance = NormalizedIntegrationFixtures.instance(
        "InsightIDR", "future_source_type", "x",
        "SIEM Event Source", IntegrationStatus.HEALTHY, "es_1", null);

    // Act
    List<VendorServiceCard> cards = aggregatorWith(snapshot)
        .toVendorServiceCards(List.of(instance));

    // Assert — synthetic, single WARN with the raw sourceType in the message
    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).vendorServiceId()).isEqualTo("unknown");
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("InsightIDR")
        .contains("future_source_type")
        .contains(MAPPING_VERSION);
}
```

- [ ] **Step 2: Run the three new tests**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — the unknown path wired in Task 5 already supports these scenarios. (Task 5's implementation went a little ahead; this task locks the contract with assertions.)

If any test fails: revisit Task 5's `resolveOne` and the WARN format constant. Common bugs: WARN format includes the enum's `.toString()` instead of the raw string; dedup `Set` is over the wrong key; one of the unmappable-enum branches forgets to call `warnOnceForTriplet`.

- [ ] **Step 3: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 4: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-02): unknown-path resolution scenarios

Three ResolutionTest scenarios lock the synthetic-card and WARN-content
contract for: snapshot returns unknown, productName string is
unmappable to ProductName enum, sourceType string is unmappable to
SourceType enum. Each asserts the synthetic identity (unknown ids,
OTHER category) and that the WARN message carries the raw triplet
values plus mappingVersion.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: DataSourceRollupTest — worst-state across instances under one DS

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

The Task 5 placeholder rolls up by taking the first instance's status. Time to drive it to a real `HealthRollup` reduction by writing a mixed-state scenario.

- [ ] **Step 1: Add the failing rollup test**

Add a new `@Nested DataSourceRollupTest` block to `VendorAggregatorTest`:

```java
@Nested
class DataSourceRollupTest {

    @Test
    void toVendorServiceCards_shouldRollUpInstanceStates_atDataSourceLevel() {
        // Arrange — three IDR instances under one data source, mixed states.
        // Worst-state: error > missing_data > warning > disabled > healthy.
        // Expected DS status: ERROR.
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-defender-endpoint", IntegrationStatus.WARNING),
            NormalizedIntegrationFixtures.idrInstance("es_3",
                "microsoft-defender-endpoint", IntegrationStatus.ERROR));

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert — single VS card, single DS, aggregate is ERROR rolled across instances
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: FAIL — the existing scaffold returns `group.get(0).instance().status()` which is `HEALTHY`. Test fails on the assertion.

- [ ] **Step 3: Replace the placeholder with a real rollup**

In `VendorAggregator.java`, replace `buildVendorServiceCard`:

```java
private VendorServiceCard buildVendorServiceCard(List<ResolvedInstance> group) {
    VendorResolution r = group.get(0).resolution();
    Map<String, List<ResolvedInstance>> byDataSourceId = groupByDataSourceId(group);
    IntegrationStatus aggregate = byDataSourceId.values().stream()
        .map(VendorAggregator::dataSourceStatus)
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
    return new VendorServiceCard(
        r.vendorServiceId(), r.vendorServiceName(), r.vendorId(), r.vendorName(),
        r.vendorCategory(),
        group.size(),
        List.of(),                                          // integrationTypeCounts — Task 9
        List.of(),                                          // productsConnected — Task 9
        aggregate,
        null);                                              // lastUpdated — Task 9
}

private static Map<String, List<ResolvedInstance>> groupByDataSourceId(List<ResolvedInstance> group) {
    Map<String, List<ResolvedInstance>> by = new LinkedHashMap<>();
    for (ResolvedInstance r : group) {
        by.computeIfAbsent(r.dataSourceId(), k -> new ArrayList<>()).add(r);
    }
    return by;
}

private static IntegrationStatus dataSourceStatus(List<ResolvedInstance> dsGroup) {
    return dsGroup.stream()
        .map(r -> r.instance().status())
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
}
```

Add the matching imports:

```java
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — all scenarios so far stay green. The Task-5 single-instance scenario still passes because `worstOf` of a one-element stream returns that element.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 6: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): worst-state rollup at data-source level

Replaces the Task-5 first-instance placeholder with HealthRollup over
each data source's instances, then HealthRollup again over the per-DS
results. Drives the DS-level rollup acceptance signal: mixed-state
instances under one data source produce ERROR.

Per-VS aggregateHealth is now correct; integrationTypeCounts,
productsConnected, and lastUpdated remain placeholders that Task 9
fills in.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: VendorServiceRollupTest — worst-state across data sources under one VS

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

Task 7 already wired the VS-level rollup into the implementation (it reduces per-DS results with `HealthRollup`). This task locks the contract with a multi-DS scenario before later tasks add complexity.

- [ ] **Step 1: Add the failing rollup test**

Append a new `@Nested VendorServiceRollupTest` block:

```java
@Nested
class VendorServiceRollupTest {

    @Test
    void toVendorServiceCards_shouldRollUpDataSourceStates_atVendorServiceLevel() {
        // Arrange — Microsoft Defender exposed via two products. Two distinct data sources.
        // DS1 (IDR) = HEALTHY; DS2 (ICON) = WARNING. VS rollup = WARNING.
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME,
                 "microsoft-defender", MS_DEFENDER)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "microsoft-defender", IntegrationStatus.WARNING));

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).aggregateHealth()).isEqualTo(IntegrationStatus.WARNING);
        assertThat(cards.get(0).integrationsConnected()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test, verify it passes**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — Task 7's implementation already handles this correctly. The TDD discipline is to lock the multi-DS contract with an assertion.

If FAIL: the most likely cause is the Task-7 code reducing instance statuses directly across the VS group instead of going through the per-DS layer. Re-check that `buildVendorServiceCard` reduces `byDataSourceId.values().stream().map(...dataSourceStatus...).reduce(...)` — i.e. **per-DS first, then VS**.

- [ ] **Step 3: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 4: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-02): worst-state rollup at vendor-service level

Two-DS scenario locks the VS-level rollup contract: HEALTHY + WARNING
across two data sources under the same vendor service rolls up to
WARNING at the VS card level, integrationsConnected counts both.

Multi-DS structure also implicitly verifies the multi-product merge
acceptance signal — IDR and ICON triplets resolve to the same
vendor_service_id and group together.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Per-VS aggregates — integrationTypeCounts, productsConnected, lastUpdated

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

Replace the placeholder `List.of()` and `null` values in `buildVendorServiceCard` with real computations.

- [ ] **Step 1: Add a `@Nested VendorServiceCardsTest` block with failing assertions**

Append:

```java
import java.time.Instant;
// ... already-present imports

@Nested
class VendorServiceCardsTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-15T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-29T10:00:00Z");

    @Test
    void toVendorServiceCards_shouldComputePerVendorServiceAggregates() {
        // Arrange — Microsoft Defender via IDR (2 instances) + ICON (1 instance).
        // Two distinct integrationTypes; one ERROR; mixed lastSuccess timestamps.
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME,
                 "microsoft-defender", MS_DEFENDER)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, T1),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-defender-endpoint", IntegrationStatus.ERROR, T2),
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "microsoft-defender", IntegrationStatus.HEALTHY, T3));

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert
        assertThat(cards).hasSize(1);
        VendorServiceCard card = cards.get(0);
        assertThat(card.integrationsConnected()).isEqualTo(3);
        assertThat(card.lastUpdated()).isEqualTo(T3);
        assertThat(card.productsConnected())
            .containsExactly("InsightIDR", "InsightConnect");
        assertThat(card.integrationTypeCounts())
            .containsExactlyInAnyOrder(
                new IntegrationTypeCount("SIEM Event Source", 2, 1),
                new IntegrationTypeCount("Automation Plugin", 1, 0));
    }

    @Test
    void toVendorServiceCards_shouldReturnNullLastUpdated_whenNoInstanceHasTimestamp() {
        // Arrange — single VS, every instance has null lastSuccessTimestamp
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, null),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, null));

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert
        assertThat(cards.get(0).lastUpdated()).isNull();
    }
}
```

- [ ] **Step 2: Run, verify both tests fail**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: the two new tests FAIL — current `buildVendorServiceCard` returns empty `integrationTypeCounts`, empty `productsConnected`, `null` lastUpdated. The `lastUpdated == null` test happens to pass by accident; the Task 5 placeholder returns `null`. **That's fine** — TDD pinning is more about the failing case (the multi-instance one) driving the implementation.

- [ ] **Step 3: Implement the aggregates**

In `VendorAggregator.java`, replace `buildVendorServiceCard` and add the helpers:

```java
private VendorServiceCard buildVendorServiceCard(List<ResolvedInstance> group) {
    VendorResolution r = group.get(0).resolution();
    Map<String, List<ResolvedInstance>> byDataSourceId = groupByDataSourceId(group);
    IntegrationStatus aggregate = byDataSourceId.values().stream()
        .map(VendorAggregator::dataSourceStatus)
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
    return new VendorServiceCard(
        r.vendorServiceId(), r.vendorServiceName(), r.vendorId(), r.vendorName(),
        r.vendorCategory(),
        group.size(),
        integrationTypeCounts(group),
        productsConnected(group),
        aggregate,
        latestSuccess(group));
}

private static List<IntegrationTypeCount> integrationTypeCounts(List<ResolvedInstance> group) {
    Map<String, int[]> totals = new LinkedHashMap<>();          // type -> {total, errorCount}
    for (ResolvedInstance r : group) {
        String type = r.instance().integrationType();
        int[] counts = totals.computeIfAbsent(type, k -> new int[2]);
        counts[0]++;
        if (r.instance().status() == IntegrationStatus.ERROR) {
            counts[1]++;
        }
    }
    List<IntegrationTypeCount> out = new ArrayList<>(totals.size());
    for (Map.Entry<String, int[]> e : totals.entrySet()) {
        out.add(new IntegrationTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    return out;
}

private static List<String> productsConnected(List<ResolvedInstance> group) {
    Set<String> seen = new LinkedHashSet<>();
    for (ResolvedInstance r : group) {
        seen.add(r.instance().productName());
    }
    return List.copyOf(seen);
}

private static Instant latestSuccess(List<ResolvedInstance> group) {
    Instant max = null;
    for (ResolvedInstance r : group) {
        Instant t = r.instance().lastSuccessTimestamp();
        if (t != null && (max == null || t.isAfter(max))) {
            max = t;
        }
    }
    return max;
}
```

Add imports:

```java
import java.time.Instant;
import java.util.LinkedHashSet;
```

- [ ] **Step 4: Run, verify all green**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — all scenarios green.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 6: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. PMD: `CognitiveComplexity` and `CyclomaticComplexity` may flag `latestSuccess` if measured strictly, but the method is six lines with one `if`; should be well below the configured thresholds (default 25 / 10 respectively in the engagement's ruleset).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): per-vendor-service computed aggregates

Replaces placeholder values with real computations:
- integrationTypeCounts: one IntegrationTypeCount per distinct
  integrationType under the VS, with total and errorCount.
- productsConnected: distinct productName strings, insertion-stable.
- lastUpdated: max non-null lastSuccessTimestamp across all instances
  under the VS, null when every instance has null.

Drives the per-VS aggregates acceptance signals; lastUpdated nullability
test pins the null-when-all-null contract.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Multi-product merge + canonical data_source_id assertion

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

Two of the work plan's most-load-bearing acceptance signals: ICON + IDR with different source identifiers but same `vendor_service_id` produce **two distinct DataSourceDetails** under one VS card; and the `data_source_id` for the canonical IDR vector matches the RFC string byte-for-byte.

These signals are most naturally tested through `toVendorServiceDetail`, but that's a Task-12 implementation. We can test the merge structure NOW via `toVendorServiceCards` (count, productsConnected) and lock the data_source_id behavior with a unit-style assertion through a vendor-scoped detail call once Task 12 lands. **For this task, lock the visible-via-cards behavior; Task 12 adds the deeper data_source_id round-trip.**

- [ ] **Step 1: Add scenarios to `VendorServiceCardsTest`**

Append to the existing `VendorServiceCardsTest` block:

```java
@Test
void toVendorServiceCards_shouldMergeMultipleProducts_intoOneVendorServiceCard() {
    // Arrange — Microsoft Defender via three products: IDR, ICON, and a hypothetical
    // unknown product instance. The first two map to the same vendor_service_id;
    // the third is unknown.
    VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
        .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
             "microsoft-defender-endpoint", MS_DEFENDER)
        .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME,
             "microsoft-defender", MS_DEFENDER)
        .build();

    List<NormalizedIntegration> instances = List.of(
        NormalizedIntegrationFixtures.idrInstance("es_1",
            "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
        NormalizedIntegrationFixtures.iconInstance("c_1",
            "microsoft-defender", IntegrationStatus.HEALTHY));

    // Act
    List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

    // Assert — exactly ONE Microsoft Defender card
    assertThat(cards).hasSize(1);
    VendorServiceCard card = cards.get(0);
    assertThat(card.vendorServiceId()).isEqualTo("microsoft-defender");
    assertThat(card.integrationsConnected()).isEqualTo(2);
    assertThat(card.productsConnected())
        .containsExactlyInAnyOrder("InsightIDR", "InsightConnect");
}
```

- [ ] **Step 2: Run, verify it passes**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — Tasks 5–9 already wired the merge correctly. This locks the visible contract.

(The data_source_id canonical-string assertion happens in Task 12 once `toVendorServiceDetail` is implemented and we can read the data sources directly.)

- [ ] **Step 3: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 4: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-02): multi-product merge into one vendor-service card

ICON + IDR triplets resolving to the same vendor_service_id produce
ONE VendorServiceCard with integrationsConnected=2 and both products
listed in productsConnected. Locks the visible side of the
multi-product merge acceptance signal; data_source_id round-trip
lands with toVendorServiceDetail in Task 12.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: toVendorCards — narrow projection

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

The `/vendors` projection is intentionally narrow: `vendor_id`, `vendor_name`, `vendor_services_count` only. No health, no category.

- [ ] **Step 1: Add the failing test**

Append a new `@Nested VendorCardsTest`:

```java
@Nested
class VendorCardsTest {

    @Test
    void toVendorCards_shouldEmitOneCardPerDistinctVendor_withVendorServicesCount() {
        // Arrange — two Microsoft services (Defender + Sentinel) plus one Atlassian (Jira).
        // Microsoft has 2 vendor services; Atlassian has 1.
        VendorResolution msSentinel = new VendorResolution(
            "microsoft-sentinel", "Microsoft Sentinel", VendorCategory.SIEM,
            "microsoft", "Microsoft");
        VendorResolution jira = new VendorResolution(
            "jira", "Jira", VendorCategory.ITSM,
            "atlassian", "Atlassian");

        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-sentinel", msSentinel)
            .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME,
                 "jira", jira)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-sentinel", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "jira", IntegrationStatus.HEALTHY));

        // Act
        List<VendorCard> vendors = aggregatorWith(snapshot).toVendorCards(instances);

        // Assert
        assertThat(vendors).hasSize(2);
        VendorCard microsoft = vendors.stream()
            .filter(v -> v.vendorId().equals("microsoft")).findFirst().orElseThrow();
        VendorCard atlassian = vendors.stream()
            .filter(v -> v.vendorId().equals("atlassian")).findFirst().orElseThrow();
        assertThat(microsoft.vendorName()).isEqualTo("Microsoft");
        assertThat(microsoft.vendorServicesCount()).isEqualTo(2);
        assertThat(atlassian.vendorName()).isEqualTo("Atlassian");
        assertThat(atlassian.vendorServicesCount()).isEqualTo(1);
    }

    @Test
    void toVendorCards_shouldEmitSyntheticUnknownVendor_whenUnmappedTripletPresent() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.iconInstance("c_1", "new-product",
                IntegrationStatus.HEALTHY));

        // Act
        List<VendorCard> vendors = aggregatorWith(snapshot).toVendorCards(instances);

        // Assert — single synthetic vendor card with vendorServicesCount=1
        assertThat(vendors).hasSize(1);
        assertThat(vendors.get(0).vendorId()).isEqualTo("unknown");
        assertThat(vendors.get(0).vendorName()).isEqualTo("Unknown");
        assertThat(vendors.get(0).vendorServicesCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: FAIL — `toVendorCards` is currently a stub returning `List.of()`.

- [ ] **Step 3: Implement `toVendorCards`**

In `VendorAggregator.java`, replace the `toVendorCards` method body:

```java
public List<VendorCard> toVendorCards(List<NormalizedIntegration> instances) {
    Objects.requireNonNull(instances, "instances");
    if (instances.isEmpty()) {
        return List.of();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    return buildVendorCards(resolved);
}

private static List<VendorCard> buildVendorCards(List<ResolvedInstance> resolved) {
    // vendorId -> (vendorName, set-of-vendorServiceIds)
    Map<String, VendorAccumulator> byVendorId = new LinkedHashMap<>();
    for (ResolvedInstance r : resolved) {
        VendorResolution res = r.resolution();
        VendorAccumulator acc = byVendorId.computeIfAbsent(
            res.vendorId(), k -> new VendorAccumulator(res.vendorName()));
        acc.vendorServiceIds.add(res.vendorServiceId());
    }
    List<VendorCard> cards = new ArrayList<>(byVendorId.size());
    for (Map.Entry<String, VendorAccumulator> e : byVendorId.entrySet()) {
        cards.add(new VendorCard(
            e.getKey(), e.getValue().vendorName, e.getValue().vendorServiceIds.size()));
    }
    return cards;
}

private static final class VendorAccumulator {
    private final String vendorName;
    private final Set<String> vendorServiceIds = new LinkedHashSet<>();

    private VendorAccumulator(String vendorName) {
        this.vendorName = vendorName;
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 6: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. PMD: a private static class with one field is fine (`TooManyFields` won't fire on one field; `CouplingBetweenObjects` is computed at class level and we are well under the threshold).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): toVendorCards narrow projection

Implements the /vendors filter feed: one VendorCard per distinct
resolved vendorId, with vendorServicesCount = distinct
vendor_service_ids under that vendor. No aggregate_health, no
category — matches RFC §Read API Contract → Projections.

Synthetic unknown vendor surfaces alongside real vendors when at
least one unmapped triplet exists in the input.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: toVendorServiceDetail — VS header + data_sources[] + integrations[]

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

This is the deepest projection. Adds `DataSourceDetail` rows under the VS header; each carries its own `IntegrationDetail[]`. Locks the canonical `data_source_id` round-trip (Task 10's deferred assertion lands here).

- [ ] **Step 1: Add failing tests**

Append a new `@Nested VendorServiceDetailTest`:

```java
@Nested
class VendorServiceDetailTest {

    @Test
    void toVendorServiceDetail_shouldReturnEmpty_whenIdNotFound() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY));

        // Act — ask for a different vendor service
        Optional<VendorServiceDetail> result = aggregatorWith(snapshot)
            .toVendorServiceDetail("microsoft-sentinel", instances);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void toVendorServiceDetail_shouldReturnDetailWithDataSources_whenFound() {
        // Arrange — Microsoft Defender via two products, three instances
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME,
                 "microsoft-defender", MS_DEFENDER)
            .build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-defender-endpoint", IntegrationStatus.ERROR),
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "microsoft-defender", IntegrationStatus.HEALTHY));

        // Act
        Optional<VendorServiceDetail> result = aggregatorWith(snapshot)
            .toVendorServiceDetail("microsoft-defender", instances);

        // Assert
        assertThat(result).isPresent();
        VendorServiceDetail detail = result.get();
        assertThat(detail.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(detail.integrationsConnected()).isEqualTo(3);
        assertThat(detail.aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
        assertThat(detail.dataSources()).hasSize(2);

        DataSourceDetail idrDs = detail.dataSources().stream()
            .filter(d -> d.productName().equals("InsightIDR"))
            .findFirst().orElseThrow();
        DataSourceDetail iconDs = detail.dataSources().stream()
            .filter(d -> d.productName().equals("InsightConnect"))
            .findFirst().orElseThrow();

        // Canonical data_source_id locked here — RFC §data_source_id construction
        assertThat(idrDs.dataSourceId())
            .isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
        assertThat(idrDs.integrationsCount()).isEqualTo(2);
        assertThat(idrDs.status()).isEqualTo(IntegrationStatus.ERROR);
        assertThat(idrDs.integrations()).hasSize(2);

        assertThat(iconDs.dataSourceId())
            .isEqualTo("insightconnect|plugin_name|microsoft-defender");
        assertThat(iconDs.integrationsCount()).isEqualTo(1);
        assertThat(iconDs.status()).isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void toVendorServiceDetail_shouldResolveUnknownVendorServiceId_whenUnmappedInstancesPresent() {
        // Arrange — three unmapped triplets, all collapse to the unknown VS
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "new-product-a", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_2",
                "new-product-b", IntegrationStatus.WARNING),
            NormalizedIntegrationFixtures.iconInstance("c_3",
                "new-product-c", IntegrationStatus.ERROR));

        // Act
        Optional<VendorServiceDetail> result = aggregatorWith(snapshot)
            .toVendorServiceDetail("unknown", instances);

        // Assert — synthetic VS with three distinct DSes; aggregateHealth = ERROR
        assertThat(result).isPresent();
        VendorServiceDetail detail = result.get();
        assertThat(detail.vendorServiceId()).isEqualTo("unknown");
        assertThat(detail.dataSources()).hasSize(3);
        assertThat(detail.aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
        // Each DS preserves its raw triplet in data_source_id and uses sourceValue as displayName
        assertThat(detail.dataSources())
            .extracting(DataSourceDetail::dataSourceId)
            .containsExactlyInAnyOrder(
                "insightconnect|plugin_name|new-product-a",
                "insightconnect|plugin_name|new-product-b",
                "insightconnect|plugin_name|new-product-c");
        assertThat(detail.dataSources())
            .extracting(DataSourceDetail::displayName)
            .containsExactlyInAnyOrder("new-product-a", "new-product-b", "new-product-c");
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: FAIL — `toVendorServiceDetail` returns `Optional.empty()` always.

- [ ] **Step 3: Implement `toVendorServiceDetail`**

In `VendorAggregator.java`:

```java
public Optional<VendorServiceDetail> toVendorServiceDetail(
        String vendorServiceId, List<NormalizedIntegration> instances) {
    Objects.requireNonNull(vendorServiceId, "vendorServiceId");
    Objects.requireNonNull(instances, "instances");
    if (instances.isEmpty()) {
        return Optional.empty();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    List<ResolvedInstance> scoped = resolved.stream()
        .filter(r -> vendorServiceId.equals(r.resolution().vendorServiceId()))
        .toList();
    if (scoped.isEmpty()) {
        return Optional.empty();
    }
    return Optional.of(buildVendorServiceDetail(scoped));
}

private VendorServiceDetail buildVendorServiceDetail(List<ResolvedInstance> group) {
    VendorResolution r = group.get(0).resolution();
    Map<String, List<ResolvedInstance>> byDataSourceId = groupByDataSourceId(group);
    List<DataSourceDetail> dataSources = new ArrayList<>(byDataSourceId.size());
    for (List<ResolvedInstance> dsGroup : byDataSourceId.values()) {
        dataSources.add(buildDataSourceDetail(dsGroup));
    }
    IntegrationStatus aggregate = dataSources.stream()
        .map(DataSourceDetail::status)
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
    return new VendorServiceDetail(
        r.vendorServiceId(), r.vendorServiceName(), r.vendorId(), r.vendorName(),
        r.vendorCategory(),
        group.size(),
        integrationTypeCounts(group),
        productsConnected(group),
        aggregate,
        latestSuccess(group),
        dataSources);
}

private static DataSourceDetail buildDataSourceDetail(List<ResolvedInstance> dsGroup) {
    ResolvedInstance first = dsGroup.get(0);
    NormalizedIntegration firstInstance = first.instance();
    IntegrationStatus status = dataSourceStatus(dsGroup);
    List<IntegrationDetail> integrations = new ArrayList<>(dsGroup.size());
    for (ResolvedInstance r : dsGroup) {
        NormalizedIntegration n = r.instance();
        integrations.add(new IntegrationDetail(
            n.integrationId(),
            r.dataSourceId(),
            n.integrationLabel(),
            n.status(),
            n.lastSuccessTimestamp(),
            n.configurationUrl()));
    }
    return new DataSourceDetail(
        first.dataSourceId(),
        first.displayName(),
        firstInstance.integrationType(),
        firstInstance.productName(),
        status,
        integrations.size(),
        integrations);
}
```

- [ ] **Step 4: Run, verify all green**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — all three new tests + every prior test green.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 6: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): toVendorServiceDetail with data_sources[] + integrations[]

Vendor-service detail projection: VS header (mirroring VendorServiceCard)
plus data_sources[], each carrying its own integrations[] of per-instance
detail. Returns Optional.empty() when the requested vendorServiceId has
no resolved instances; resolves vendorServiceId="unknown" against the
synthetic VS when unmapped triplets exist in input.

Locks the canonical data_source_id contract: (InsightIDR, product_type,
microsoft-defender-endpoint) round-trips to
"insightidr|product_type|microsoft-defender-endpoint". And the
multi-product merge: ICON + IDR triplets that resolve to the same
vendor_service_id appear as two distinct DataSourceDetails with
different data_source_id values.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: toVendorScopedView + Vendor-level rollup (the "trap" case) + UnknownCollapseTest + EdgeCasesTest

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java`

This task lands the last projection method, the **vendor-level rollup trap case** (per-VS rollup hides an inner instance with worse state, but per-vendor correctly picks the worst VS), the unknown-collapse end-to-end behavior, and the remaining edge cases (null args, empty input).

- [ ] **Step 1: Add four new `@Nested` blocks with failing tests**

Append:

```java
@Nested
class VendorScopedViewTest {

    @Test
    void toVendorScopedView_shouldReturnEmpty_whenIdNotFound() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "x", IntegrationStatus.HEALTHY));

        // Act
        Optional<VendorScopedView> result = aggregatorWith(snapshot)
            .toVendorScopedView("microsoft", instances);

        // Assert — input has no resolved Microsoft instances (it has only an
        // unmapped instance, which lives under "unknown" vendor)
        assertThat(result).isEmpty();
    }

    @Test
    void toVendorScopedView_shouldRollUpAcrossVendorServices_atVendorLevel() {
        // Arrange — Microsoft has two services. Defender is HEALTHY across all
        // its instances; Sentinel has one ERROR instance. Per-vendor rollup
        // must be ERROR — driven by Sentinel's VS aggregate, NOT by the
        // Defender VS aggregate.
        VendorResolution msSentinel = new VendorResolution(
            "microsoft-sentinel", "Microsoft Sentinel", VendorCategory.SIEM,
            "microsoft", "Microsoft");

        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-defender-endpoint", MS_DEFENDER)
            .map(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE,
                 "microsoft-sentinel", msSentinel)
            .build();

        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.idrInstance("es_1",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.idrInstance("es_2",
                "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.idrInstance("es_3",
                "microsoft-sentinel", IntegrationStatus.ERROR));

        // Act
        Optional<VendorScopedView> result = aggregatorWith(snapshot)
            .toVendorScopedView("microsoft", instances);

        // Assert
        assertThat(result).isPresent();
        VendorScopedView vendor = result.get();
        assertThat(vendor.vendorId()).isEqualTo("microsoft");
        assertThat(vendor.vendorName()).isEqualTo("Microsoft");
        assertThat(vendor.vendorServicesCount()).isEqualTo(2);
        assertThat(vendor.aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
        assertThat(vendor.vendorServices()).hasSize(2);
    }

    @Test
    void toVendorScopedView_shouldResolveUnknownVendorId_whenUnmappedInstancesPresent() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "new-product", IntegrationStatus.WARNING));

        // Act
        Optional<VendorScopedView> result = aggregatorWith(snapshot)
            .toVendorScopedView("unknown", instances);

        // Assert — vendorId "unknown" resolves to the synthetic vendor
        assertThat(result).isPresent();
        assertThat(result.get().vendorId()).isEqualTo("unknown");
        assertThat(result.get().vendorServicesCount()).isEqualTo(1);
        assertThat(result.get().aggregateHealth()).isEqualTo(IntegrationStatus.WARNING);
    }
}

@Nested
class UnknownCollapseTest {

    @Test
    void toVendorServiceCards_shouldCollapseAllUnmappedTriplets_intoOneSyntheticCard() {
        // Arrange — three different unmapped triplets, each yielding its own DS
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "alpha", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_2",
                "beta", IntegrationStatus.WARNING),
            NormalizedIntegrationFixtures.iconInstance("c_3",
                "gamma", IntegrationStatus.ERROR));

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert — exactly ONE synthetic VS card; aggregate ERROR; 3 instances; 3 distinct DSes
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).vendorServiceId()).isEqualTo("unknown");
        assertThat(cards.get(0).vendorId()).isEqualTo("unknown");
        assertThat(cards.get(0).vendorCategory()).isEqualTo(VendorCategory.OTHER);
        assertThat(cards.get(0).integrationsConnected()).isEqualTo(3);
        assertThat(cards.get(0).aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void toVendorServiceCards_shouldEmitOneWarn_perDistinctUnmappedTriplet() {
        // Arrange — three instances sharing a triplet + two with distinct triplets.
        // Distinct triplet count = 3.
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        List<NormalizedIntegration> instances = List.of(
            NormalizedIntegrationFixtures.iconInstance("c_1",
                "alpha", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_2",
                "alpha", IntegrationStatus.HEALTHY),     // duplicate triplet
            NormalizedIntegrationFixtures.iconInstance("c_3",
                "alpha", IntegrationStatus.HEALTHY),     // duplicate triplet
            NormalizedIntegrationFixtures.iconInstance("c_4",
                "beta", IntegrationStatus.HEALTHY),
            NormalizedIntegrationFixtures.iconInstance("c_5",
                "gamma", IntegrationStatus.HEALTHY));

        // Act
        aggregatorWith(snapshot).toVendorServiceCards(instances);

        // Assert — exactly 3 WARN events, one per distinct triplet
        assertThat(appender.list).hasSize(3);
        assertThat(appender.list).allSatisfy(e ->
            assertThat(e.getLevel().toString()).isEqualTo("WARN"));
        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(m -> m.contains("alpha") && m.contains(MAPPING_VERSION))
            .anyMatch(m -> m.contains("beta") && m.contains(MAPPING_VERSION))
            .anyMatch(m -> m.contains("gamma") && m.contains(MAPPING_VERSION));
    }
}

@Nested
class EdgeCasesTest {

    @Test
    void toVendorServiceCards_shouldReturnEmpty_forEmptyInput() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();

        // Act
        List<VendorServiceCard> cards = aggregatorWith(snapshot)
            .toVendorServiceCards(List.of());

        // Assert — empty list, no synthetic, no WARN
        assertThat(cards).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void toVendorCards_shouldReturnEmpty_forEmptyInput() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        assertThat(aggregatorWith(snapshot).toVendorCards(List.of())).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void toVendorScopedView_shouldReturnEmpty_forEmptyInput() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        assertThat(aggregatorWith(snapshot)
            .toVendorScopedView("microsoft", List.of())).isEmpty();
    }

    @Test
    void toVendorServiceDetail_shouldReturnEmpty_forEmptyInput() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        assertThat(aggregatorWith(snapshot)
            .toVendorServiceDetail("microsoft-defender", List.of())).isEmpty();
    }

    @Test
    void toVendorServiceCards_shouldThrowNPE_whenInstancesNull() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
            .isThrownBy(() -> aggregatorWith(snapshot).toVendorServiceCards(null))
            .withMessage("instances");
    }

    @Test
    void toVendorScopedView_shouldThrowNPE_whenVendorIdNull() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
            .isThrownBy(() -> aggregatorWith(snapshot)
                .toVendorScopedView(null, List.of()))
            .withMessage("vendorId");
    }

    @Test
    void toVendorServiceDetail_shouldThrowNPE_whenVendorServiceIdNull() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
            .isThrownBy(() -> aggregatorWith(snapshot)
                .toVendorServiceDetail(null, List.of()))
            .withMessage("vendorServiceId");
    }

    @Test
    void resolution_shouldThrowIAE_whenAdapterSuppliedProductNameIsBlank() {
        // Arrange — adapter contract violation: blank productName.
        // Spec ruling: programming-error path, not folded into unknown.
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        NormalizedIntegration instance = NormalizedIntegrationFixtures.instance(
            "   ", "product_type", "x", "T", IntegrationStatus.HEALTHY, "i_1", null);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> aggregatorWith(snapshot).toVendorServiceCards(List.of(instance)))
            .withMessageContaining("productName");
    }

    @Test
    void resolution_shouldThrowIAE_whenAdapterSuppliedSourceTypeIsBlank() {
        VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
        NormalizedIntegration instance = NormalizedIntegrationFixtures.instance(
            "InsightIDR", "   ", "x", "T", IntegrationStatus.HEALTHY, "i_1", null);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> aggregatorWith(snapshot).toVendorServiceCards(List.of(instance)))
            .withMessageContaining("sourceType");
    }
}
```

The trap-case test in `VendorScopedViewTest` is the load-bearing one for the work plan's "rollup is across services, not directly across instances" acceptance signal. The assertion: vendor `microsoft` rolls to ERROR because Sentinel's VS aggregate is ERROR, even though Defender's VS aggregate is HEALTHY.

- [ ] **Step 2: Run, verify failure**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: many failures —
- `toVendorScopedView` is still a stub returning `Optional.empty()`.
- `EdgeCasesTest` cases for `null` args pass on `toVendorServiceCards` and `toVendorScopedView`/`toVendorServiceDetail` (NPE wired in Tasks 5 / 12).
- The two IAE tests pass already — the resolution helper delegates to the String mint overload, which validates non-blank.

The implementation tasks below address the failures.

- [ ] **Step 3: Implement `toVendorScopedView`**

In `VendorAggregator.java`:

```java
public Optional<VendorScopedView> toVendorScopedView(
        String vendorId, List<NormalizedIntegration> instances) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(instances, "instances");
    if (instances.isEmpty()) {
        return Optional.empty();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    List<ResolvedInstance> scoped = resolved.stream()
        .filter(r -> vendorId.equals(r.resolution().vendorId()))
        .toList();
    if (scoped.isEmpty()) {
        return Optional.empty();
    }
    return Optional.of(buildVendorScopedView(scoped));
}

private VendorScopedView buildVendorScopedView(List<ResolvedInstance> scoped) {
    VendorResolution v = scoped.get(0).resolution();
    List<VendorServiceCard> services = buildVendorServiceCards(scoped);
    IntegrationStatus aggregate = services.stream()
        .map(VendorServiceCard::aggregateHealth)
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
    Instant lastUpdated = latestSuccess(scoped);
    return new VendorScopedView(
        v.vendorId(),
        v.vendorName(),
        services.size(),
        aggregate,
        lastUpdated,
        services);
}
```

Note the per-vendor rollup is over `services.stream().map(VendorServiceCard::aggregateHealth)` — which itself rolls per-DS, which itself rolls per-instance. The trap case is satisfied because the chain is per-instance → per-DS → per-VS → per-vendor; a single ERROR instance under any DS under any VS under the vendor wins all the way up.

- [ ] **Step 4: Run, verify all green**

Run: `./mvnw -q test -Dtest=VendorAggregatorTest`
Expected: PASS — every scenario green.

If `resolution_shouldThrowIAE_whenAdapterSuppliedSourceTypeIsBlank` fails: the bug is in `resolveOne` — when `SourceType.fromWireForm("   ")` returns empty, the unknown-path branch calls `DataSourceIdMinter.mint(rawProductName, "   ", sourceValue)`, which the String overload rejects with the right IAE. The test should pass; if it doesn't, double-check that `resolveOne`'s `else` branch passes `rawSourceType` (not the converted form) to the String mint overload.

- [ ] **Step 5: Format with Spotless**

Run: `./mvnw -q spotless:apply`

- [ ] **Step 6: Run full verify**

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. PMD `GodClass`: `VendorAggregator` now has ~15 methods; check the `TooManyMethods` threshold (default 10 for the rule, but the engagement's PMD ruleset uses defaults — confirm by reading `pmd-ruleset.xml`'s `TooManyMethods` rule). If it fires, there are two reasonable fixes:

1. **Extract** the per-projection builders (`buildVendorServiceCards`, `buildVendorServiceCard`, `buildVendorCards`, `buildVendorScopedView`, `buildVendorServiceDetail`, `buildDataSourceDetail`, helpers) into a package-private `aggregator/internal/ProjectionBuilder` class. The spec calls this out as a planned-if-needed split.

2. **Suppress locally** with `@SuppressWarnings("PMD.TooManyMethods")` on the class with a justification comment ("All methods are part of the four projection responsibilities and the shared resolution pass — splitting into projection-specific helpers would scatter the resolution context.").

Pick option 1 if the rule fires — keeps the suppression count zero in line with the engagement's quality posture. Option 2 only if extraction creates more friction than it removes (e.g. resolution context becomes cumbersome to thread).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java \
        src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-02): toVendorScopedView + vendor-level rollup + edge cases

Lands the last public projection method:
- toVendorScopedView returns Optional, rolls aggregateHealth across the
  vendor's services (NOT directly across instances — the per-VS rollup
  is computed first, then reduced again at the vendor layer).
- vendorId="unknown" resolves the same way as any real id when unmapped
  triplets exist in the input.

Locks the rollup-trap acceptance signal: a single ERROR instance under
ONE service of a multi-service vendor produces ERROR at the vendor
level, even when every other service is HEALTHY.

UnknownCollapseTest pins the deduped-WARN contract: 5 instances over 3
distinct triplets produce 3 WARN events whose messages each carry the
raw triplet plus mappingVersion.

EdgeCasesTest pins null-arg NPEs, empty-input empties, and the
programming-error IAEs for blank productName / blank sourceType.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Final verification, plan-handoff to execute-plan

**Files:** read-only, no code changes.

- [ ] **Step 1: Use `superpowers:verification-before-completion`**

Verify, with evidence, every acceptance signal from the spec is exercised by at least one test.

Run: `./mvnw -q verify`
Expected: `BUILD SUCCESS`. Output includes `[INFO] BUILD SUCCESS` and the JUnit, ArchUnit, and PMD steps all show no failures.

- [ ] **Step 2: Confirm test coverage map**

Open `VendorAggregatorTest.java` and walk every acceptance signal in the spec's "Acceptance signals" section against the `@Nested` block that exercises it:

| Acceptance signal | Test class |
|---|---|
| Worst-state at DS level for mixed-state instances under one DS | `DataSourceRollupTest` |
| Worst-state at VS level for mixed-state DSes under one VS | `VendorServiceRollupTest` |
| Worst-state at vendor level for mixed-state VSes (rollup across VSes) | `VendorScopedViewTest.toVendorScopedView_shouldRollUpAcrossVendorServices_atVendorLevel` |
| Multi-product merge: ICON + IDR → ONE VS card, TWO distinct DSes | `VendorServiceCardsTest.toVendorServiceCards_shouldMergeMultipleProducts_intoOneVendorServiceCard` + `VendorServiceDetailTest.toVendorServiceDetail_shouldReturnDetailWithDataSources_whenFound` |
| `data_source_id` for IDR canonical example | `VendorServiceDetailTest.toVendorServiceDetail_shouldReturnDetailWithDataSources_whenFound` |
| 3 unmapped triplets → 1 synthetic VS, 3 distinct DSes; WARN count = 3 | `UnknownCollapseTest` (both tests) |
| WARN log content carries raw values + mappingVersion | `ResolutionTest` (3 scenarios) + `UnknownCollapseTest.toVendorServiceCards_shouldEmitOneWarn_perDistinctUnmappedTriplet` |
| Unmappable enum strings routed through unknown path | `ResolutionTest.toVendorServiceCards_shouldRouteToUnknownPath_when{ProductName,SourceType}StringIsUnmappable` |
| Empty input → empty results, no synthetic, no WARN | `EdgeCasesTest` (4 empty-input scenarios) |
| All-unknown input → exactly one synthetic card | `UnknownCollapseTest.toVendorServiceCards_shouldCollapseAllUnmappedTriplets_intoOneSyntheticCard` |
| Per-VS aggregates correct for multi-type, multi-error scenarios | `VendorServiceCardsTest.toVendorServiceCards_shouldComputePerVendorServiceAggregates` |
| `productsConnected` distinct preservation | same test |
| `lastUpdated` is max non-null; null when all null | `VendorServiceCardsTest` (both tests) |
| `/vendors` projection narrow shape (no aggregate_health, no category) | `VendorCardsTest` (both tests, plus `VendorCard` record itself enforces narrow shape via Plan 01 invariants) |
| `/vendors/{id}` carries aggregateHealth + nested vendor services list | `VendorScopedViewTest.toVendorScopedView_shouldRollUpAcrossVendorServices_atVendorLevel` |
| `/vendor-services/{id}` (detail) carries data_sources[] with integrations[] | `VendorServiceDetailTest.toVendorServiceDetail_shouldReturnDetailWithDataSources_whenFound` |
| `Optional.empty()` when scoped id has no instances | `VendorServiceDetailTest.toVendorServiceDetail_shouldReturnEmpty_whenIdNotFound` + `VendorScopedViewTest.toVendorScopedView_shouldReturnEmpty_whenIdNotFound` |
| `vendorId="unknown"` / `vendorServiceId="unknown"` RESOLVE | `VendorScopedViewTest.toVendorScopedView_shouldResolveUnknownVendorId_whenUnmappedInstancesPresent` + `VendorServiceDetailTest.toVendorServiceDetail_shouldResolveUnknownVendorServiceId_whenUnmappedInstancesPresent` |
| Whitespace-only productName / sourceType, `\|`-containing sourceValue → IAE | `EdgeCasesTest.resolution_shouldThrowIAE_when*` (productName + sourceType) + `DataSourceIdMinterStringOverloadTest` (sourceValue with `\|`, sourceType with `\|`, sourceValue empty) |
| ArchUnit + PMD + Spotless + JUnit pass; `./mvnw verify` stays green | Step 1 above (Spotless `:check` is bound to the verify phase by commit `2f8aaba`) |

If any row above is unsupported by an assertion, add a test for it now and loop back through the relevant earlier task's verification.

- [ ] **Step 3: Confirm baseline file frame matches what landed**

Run: `git status` and `git diff --stat main...HEAD`

Expected file changes (count, +/-, paths) match the spec's File frame:
- `src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java` — modified.
- `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java` — new.
- `src/main/java/com/rapid7/integrationregistry/aggregator/ResolvedInstance.java` — new.
- `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java` — new.
- `src/test/java/com/rapid7/integrationregistry/aggregator/FakeVendorMappingSnapshot.java` — new.
- `src/test/java/com/rapid7/integrationregistry/aggregator/NormalizedIntegrationFixtures.java` — new.
- `src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterStringOverloadTest.java` — new.
- `docs/superpowers/specs/2026-05-29-track-08-wp-02-vendor-aggregator-design.md` — new (committed earlier in the worktree).
- `docs/superpowers/plans/2026-05-29-track-08-wp-02-vendor-aggregator.md` — this plan, new.

If anything else changed (an existing test edited, an unrelated file, etc.), stop and explain to the user before declaring complete.

- [ ] **Step 4: Hand control back to `execute-plan`**

**STOP HERE.** Do NOT auto-invoke `superpowers:finishing-a-development-branch`. The implementation is complete from this plan's perspective; `execute-plan` runs three more gates before deciding what to do with the diff:

- Phase 7 — functional review gate (multi-dimensional + adversarial autonomy review)
- Phase 8 — simplify gate
- Phase 9 — external code review

Return control with a short summary of what landed:

> "All 14 tasks complete. `./mvnw verify` is green (JUnit + ArchUnit + PMD). 13 commits on `worktree-track-08-wp-02`. Returning control to `execute-plan` for Phase 7."

`execute-plan` takes it from here.
