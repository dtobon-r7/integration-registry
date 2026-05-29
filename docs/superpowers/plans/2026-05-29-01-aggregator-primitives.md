# Aggregator Primitives and Projection Records — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the typed surface (seven projection records) and pure logic (`HealthRollup`, `DataSourceIdMinter`) the rest of Track 08 and Track 09's read API will compose against — committed as one PR with strict TDD per primitive and `./mvnw verify` green at the end.

**Architecture:** All deliverables land under `com.rapid7.integrationregistry.aggregator`. Two `final` utility classes with private constructors and public static methods (no instances) plus seven `record` types using the existing repo convention (`static final String FIELD_*` constants paired with `Objects.requireNonNull` and `List.copyOf` defensive copying). One ArchUnit rule edit (rename + narrow the deny list) lets the aggregator package depend on `..adapter..` so the existing `IntegrationStatus` enum can be reused without redefinition.

**Tech Stack:** Java 25 records and sealed switch, JUnit 5, AssertJ, ArchUnit, PMD 7.17.0, Maven (wrapper). No Spring annotations on any new type.

**Inputs:**
- Spec: [`docs/superpowers/specs/2026-05-29-01-aggregator-primitives-design.md`](../specs/2026-05-29-01-aggregator-primitives-design.md)
- Work plan: [`engagements/.../tracks/08-vendor-aggregator-and-health-rollup/work-plans/01-aggregator-primitives.md`](../../../../../engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/work-plans/01-aggregator-primitives.md)
- Worktree: `.claude/worktrees/track-08-wp-01` on branch `worktree-track-08-wp-01`

**Stop after Task 11.** Do NOT auto-invoke `superpowers:finishing-a-development-branch`. The parent execute-plan flow has gates (Phase 7 functional review, Phase 8 simplify, Phase 9 external review) that run before close-out.

---

## File Structure

**Files to create** under `src/main/java/com/rapid7/integrationregistry/aggregator/`:

| File | Responsibility |
|---|---|
| `HealthRollup.java` | Pure binary worst-state-wins precedence over `IntegrationStatus` |
| `DataSourceIdMinter.java` | Pure mint helper for the canonical `data_source_id` per RFC formula |
| `IntegrationTypeCount.java` | Record `(integrationType, total, errorCount)` with the `errorCount <= total` invariant |
| `VendorCard.java` | Record for `/vendors` projection — narrow shape, no `aggregate_health` |
| `VendorServiceCard.java` | Record for `/vendor-services` projection — full computed set, 10 fields |
| `VendorScopedView.java` | Record for `/vendors/{id}` projection — vendor header + nested vendor-service list |
| `VendorServiceDetail.java` | Record for `/vendor-services/{id}` projection — header + `dataSources`, 11 fields |
| `DataSourceDetail.java` | Record nested under `VendorServiceDetail` — data-source header + nested `integrations` |
| `IntegrationDetail.java` | Record nested under `DataSourceDetail` — per-instance fields |

**Files to create** under `src/test/java/com/rapid7/integrationregistry/aggregator/`:

| File | Responsibility |
|---|---|
| `HealthRollupTest.java` | Parametrized 5×5 matrix + symmetry + reflexive + NPE |
| `DataSourceIdMinterTest.java` | Three RFC vectors + Turkish-locale stability + rejection cases |
| `ProjectionRecordsTest.java` | One `@Nested` class per record — happy path, NPE per field, nullability, defensive copy, invariants |

**Files to modify**:

| File | Change |
|---|---|
| `src/main/java/com/rapid7/integrationregistry/aggregator/package-info.java` | Broaden Javadoc to two sentences covering the typed-surface role |
| `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java` | Rename `aggregatorLayer_shouldNotDependOnNonMappingLayers` → `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping`; remove `..adapter..` from the deny list |
| `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java` | Update the `@ArchTest` field name and right-hand reference (lines 28-29) to match the renamed rule |

**Commit cadence:** one commit per TDD pair (failing test commit, then passing impl commit). Mirror the recent track-04 history (`test(track-04/wp-03): ...`, `feat(track-04/wp-03): ...`). Use `track-08/wp-01:` prefix.

---

## Task 1: Pre-flight — plant the test pattern

**Files:** read-only

- [ ] **Step 1: Read the spec end-to-end**

Read: `docs/superpowers/specs/2026-05-29-01-aggregator-primitives-design.md`

Confirm in your head: which records have nullable fields, which records carry the PMD suppression, and what the ArchUnit rule's before/after looks like.

- [ ] **Step 2: Read the existing test pattern reference**

Read: `src/test/java/com/rapid7/integrationregistry/adapter/NormalizedIntegrationTest.java`

Note specifically:
- `static final String INTEGRATION_ID = "c_456";` — test fixtures as `static final` constants at the top of the class
- `// Arrange / // Act / // Assert` blocks inside each `@Test`
- `assertThatNullPointerException().isThrownBy(...).withMessage(NormalizedIntegration.FIELD_INTEGRATION_ID)` — package-private `FIELD_*` constants are referenced directly from tests in the same package
- Test method naming: `methodName_shouldDoX_whenY` (e.g. `constructor_shouldThrowNPE_whenStatusNull`)
- Helper extraction: `assertNpeFromCtor(ThrowingCallable, String)` — when a pattern repeats, extract a private static helper at the bottom

- [ ] **Step 3: Read the existing layer dependency rules**

Read: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
Read: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`

Lines 32-35 of `LayerDependencyRules.java` are the rule that must change. Lines 27-29 of `LayerDependencyRulesTest.java` are the corresponding `@ArchTest` field that must be renamed.

- [ ] **Step 4: Read the existing aggregator package-info**

Read: `src/main/java/com/rapid7/integrationregistry/aggregator/package-info.java`

Today it says only `Vendor-service grouping and worst-state-wins health rollup.` That gets broadened in Task 10.

- [ ] **Step 5: Verify the baseline build is green**

Run: `./mvnw verify -q`
Expected: BUILD SUCCESS. If it isn't already, stop and ask — the rest of the plan assumes a green starting point.

- [ ] **Step 6: No commit for Task 1** — read-only.

---

## Task 2: HealthRollup — failing test

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/HealthRollupTest.java`

- [ ] **Step 1: Write the failing test**

Create the test file with the full coverage matrix.

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.rapid7.integrationregistry.adapter.IntegrationStatus.DISABLED;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.ERROR;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.HEALTHY;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.MISSING_DATA;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class HealthRollupTest {

    @ParameterizedTest(name = "worstOf({0}, {1}) = {2}")
    @MethodSource("statusPairs")
    void worstOf_shouldReturnRfcWorstState_forEveryPair(
        IntegrationStatus a, IntegrationStatus b, IntegrationStatus expected) {
        // Act
        IntegrationStatus result = HealthRollup.worstOf(a, b);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "worstOf({0}, {1}) is symmetric")
    @MethodSource("statusPairs")
    void worstOf_shouldBeSymmetric_forEveryPair(
        IntegrationStatus a, IntegrationStatus b, IntegrationStatus expected) {
        // Act
        IntegrationStatus forward = HealthRollup.worstOf(a, b);
        IntegrationStatus reverse = HealthRollup.worstOf(b, a);

        // Assert
        assertThat(forward).isEqualTo(reverse);
    }

    @Test
    void worstOf_shouldThrowNPE_whenFirstArgNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> HealthRollup.worstOf(null, HEALTHY))
            .withMessage("a");
    }

    @Test
    void worstOf_shouldThrowNPE_whenSecondArgNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> HealthRollup.worstOf(HEALTHY, null))
            .withMessage("b");
    }

    /**
     * 5x5 matrix of all ordered status pairs with the RFC-001 expected worst state.
     * Precedence: error > missing_data > warning > disabled > healthy.
     */
    private static Stream<Arguments> statusPairs() {
        return Stream.of(
            // ERROR row — error wins against everything
            Arguments.of(ERROR, ERROR, ERROR),
            Arguments.of(ERROR, MISSING_DATA, ERROR),
            Arguments.of(ERROR, WARNING, ERROR),
            Arguments.of(ERROR, DISABLED, ERROR),
            Arguments.of(ERROR, HEALTHY, ERROR),
            // MISSING_DATA row
            Arguments.of(MISSING_DATA, ERROR, ERROR),
            Arguments.of(MISSING_DATA, MISSING_DATA, MISSING_DATA),
            Arguments.of(MISSING_DATA, WARNING, MISSING_DATA),
            Arguments.of(MISSING_DATA, DISABLED, MISSING_DATA),
            Arguments.of(MISSING_DATA, HEALTHY, MISSING_DATA),
            // WARNING row
            Arguments.of(WARNING, ERROR, ERROR),
            Arguments.of(WARNING, MISSING_DATA, MISSING_DATA),
            Arguments.of(WARNING, WARNING, WARNING),
            Arguments.of(WARNING, DISABLED, WARNING),
            Arguments.of(WARNING, HEALTHY, WARNING),
            // DISABLED row
            Arguments.of(DISABLED, ERROR, ERROR),
            Arguments.of(DISABLED, MISSING_DATA, MISSING_DATA),
            Arguments.of(DISABLED, WARNING, WARNING),
            Arguments.of(DISABLED, DISABLED, DISABLED),
            Arguments.of(DISABLED, HEALTHY, DISABLED),
            // HEALTHY row
            Arguments.of(HEALTHY, ERROR, ERROR),
            Arguments.of(HEALTHY, MISSING_DATA, MISSING_DATA),
            Arguments.of(HEALTHY, WARNING, WARNING),
            Arguments.of(HEALTHY, DISABLED, DISABLED),
            Arguments.of(HEALTHY, HEALTHY, HEALTHY)
        );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=HealthRollupTest -q`
Expected: COMPILATION FAILURE — `cannot find symbol: class HealthRollup`. (Test cannot even compile because the production class doesn't exist yet — that's correct TDD-red state.)

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/HealthRollupTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-01): add HealthRollupTest covering 5x5 matrix and NPE

Parametrized coverage of every ordered IntegrationStatus pair plus
symmetry property and null-rejection. Test compiles against a
HealthRollup type that does not yet exist — the implementation lands
in the next commit per TDD.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: HealthRollup — implementation

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/HealthRollup.java`

- [ ] **Step 1: Implement the production class**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.util.Objects;

/**
 * Worst-state-wins reduction over the RFC-001 status precedence:
 * {@code error > missing_data > warning > disabled > healthy}.
 *
 * <p>Call sites that need to reduce a stream of statuses can use
 * {@code stream.reduce(HealthRollup::worstOf)} directly.
 */
public final class HealthRollup {

    private HealthRollup() {}

    public static IntegrationStatus worstOf(IntegrationStatus a, IntegrationStatus b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(IntegrationStatus s) {
        return switch (s) {
            case ERROR        -> 4;
            case MISSING_DATA -> 3;
            case WARNING      -> 2;
            case DISABLED     -> 1;
            case HEALTHY      -> 0;
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./mvnw test -Dtest=HealthRollupTest -q`
Expected: BUILD SUCCESS, all 52 tests pass (25 matrix + 25 symmetry + 2 NPE).

- [ ] **Step 3: Commit the implementation**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/HealthRollup.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-01): add HealthRollup worst-state-wins precedence function

Pure binary primitive over the existing IntegrationStatus enum,
implementing the RFC-001 ordering error > missing_data > warning >
disabled > healthy. Exhaustive switch over the enum so the build
breaks if a new state is added without an explicit rank.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: DataSourceIdMinter — failing test

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DataSourceIdMinterTest {

    // RFC-001 §Data Model → data_source_id construction — three canonical examples.
    @Test
    void mint_shouldProduceCanonicalId_forInsightIdrProductTypeVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "InsightIDR", SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
    }

    @Test
    void mint_shouldProduceCanonicalId_forInsightConnectPluginNameVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "InsightConnect", SourceType.PLUGIN_NAME, "microsoft-defender");

        // Assert
        assertThat(result).isEqualTo("insightconnect|plugin_name|microsoft-defender");
    }

    @Test
    void mint_shouldProduceCanonicalId_forSurfaceCommandIntegrationIdVector() {
        // Act
        String result = DataSourceIdMinter.mint(
            "Surface Command", SourceType.INTEGRATION_ID, "com.rapid7.microsoft-defender-for-endpoint");

        // Assert
        assertThat(result).isEqualTo("surface-command|integration_id|com.rapid7.microsoft-defender-for-endpoint");
    }

    @Test
    void mint_shouldProduceCanonicalId_evenWhenDefaultLocaleIsTurkish() {
        // Arrange — Turkish locale lowercases 'I' to lowercase-dotless-ı by default;
        // RFC-001 demands a deterministic ASCII slug, so the minter MUST use Locale.ROOT.
        Locale original = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            // Act
            String result = DataSourceIdMinter.mint(
                "InsightIDR", SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

            // Assert
            assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void mint_shouldThrowNPE_whenProductNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint(null, SourceType.PRODUCT_TYPE, "x"))
            .withMessage("productName");
    }

    @Test
    void mint_shouldThrowNPE_whenSourceTypeNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", null, "x"))
            .withMessage("sourceType");
    }

    @Test
    void mint_shouldThrowNPE_whenSourceValueNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, null))
            .withMessage("sourceValue");
    }

    @Test
    void mint_shouldThrowIAE_whenProductNameEmpty() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("", SourceType.PRODUCT_TYPE, "x"))
            .withMessageContaining("productName")
            .withMessageContaining("empty");
    }

    @Test
    void mint_shouldThrowIAE_whenSourceValueEmpty() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, ""))
            .withMessageContaining("sourceValue")
            .withMessageContaining("empty");
    }

    @Test
    void mint_shouldThrowIAE_whenSourceValueContainsDelimiter() {
        // Arrange — '|' would make the composite ambiguously parseable
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, "a|b"))
            .withMessageContaining("sourceValue")
            .withMessageContaining("|");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DataSourceIdMinterTest -q`
Expected: COMPILATION FAILURE — `cannot find symbol: class DataSourceIdMinter`.

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-01): add DataSourceIdMinterTest with three RFC vectors

Byte-for-byte assertions for the three canonical RFC-001 examples,
plus a Turkish-locale stability test (Locale.tr_TR must not break
the lowercase slug) and rejection cases for null, empty, and
'|'-bearing inputs. Test compiles against a DataSourceIdMinter type
that does not yet exist.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: DataSourceIdMinter — implementation

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java`

- [ ] **Step 1: Implement the production class**

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
 *                  + '|' + sourceType.wireForm()
 *                  + '|' + sourceValue</pre>
 */
public final class DataSourceIdMinter {

    private static final char DELIMITER = '|';

    private DataSourceIdMinter() {}

    /**
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code productName} or {@code sourceValue}
     *     is empty, or if {@code sourceValue} contains '|' (which would make the
     *     composite ambiguously parseable)
     */
    public static String mint(String productName, SourceType sourceType, String sourceValue) {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceValue, "sourceValue");
        if (productName.isEmpty()) {
            throw new IllegalArgumentException("productName must not be empty");
        }
        if (sourceValue.isEmpty()) {
            throw new IllegalArgumentException("sourceValue must not be empty");
        }
        if (sourceValue.indexOf(DELIMITER) >= 0) {
            throw new IllegalArgumentException(
                "sourceValue must not contain '|': " + sourceValue);
        }
        String slug = productName.toLowerCase(Locale.ROOT).replace(' ', '-');
        return slug + DELIMITER + sourceType.wireForm() + DELIMITER + sourceValue;
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DataSourceIdMinterTest -q`
Expected: BUILD SUCCESS, all 10 tests pass.

- [ ] **Step 3: Commit the implementation**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-01): add DataSourceIdMinter for canonical data_source_id

Pure mint helper implementing the RFC-001 formula
lower(productName).replace(' ', '-') + '|' + sourceType + '|' + sourceValue.
Locale.ROOT keeps the slug deterministic across host locales.
Defensive validation rejects empty inputs and '|'-bearing source values.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: ArchUnit rule — relax aggregator-on-adapter and update test

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java:32-35`
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java:27-29`

This task lands BEFORE the records so they can reference `IntegrationStatus` from `..adapter..` without breaking the build.

- [ ] **Step 1: Edit `LayerDependencyRules.java`**

Replace the rule definition at lines 32-35 (and update the import sort if needed). The change is two lines: a constant rename and a four-package deny list shrunk to three packages.

Before:
```java
    static final ArchRule aggregatorLayer_shouldNotDependOnNonMappingLayers =
            noClasses().that().resideInAPackage("..aggregator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..", "..aggregator..", "..mapping..");
```

Wait — the spec quotes the rule with `..adapter..` in the deny list, which is what the existing file actually contains. Re-read the live file carefully before editing.

Live file at lines 32-35 (verified via Read in Task 1):
```java
    static final ArchRule aggregatorLayer_shouldNotDependOnNonMappingLayers =
            noClasses().that().resideInAPackage("..aggregator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..", "..adapter..");
```

After the edit:
```java
    static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
            noClasses().that().resideInAPackage("..aggregator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..");
```

- [ ] **Step 2: Edit `LayerDependencyRulesTest.java` lines 27-29**

Before:
```java
    @ArchTest
    static final ArchRule aggregatorLayer_shouldNotDependOnNonMappingLayers =
            LayerDependencyRules.aggregatorLayer_shouldNotDependOnNonMappingLayers;
```

After:
```java
    @ArchTest
    static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
            LayerDependencyRules.aggregatorLayer_shouldOnlyDependOnAdapterAndMapping;
```

- [ ] **Step 3: Run the ArchUnit test to verify the rename compiles and the rule still passes**

Run: `./mvnw test -Dtest=LayerDependencyRulesTest -q`
Expected: BUILD SUCCESS. All 8 ArchUnit checks pass — including the renamed `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping` (no current code in the aggregator package depends on controller/service/coordinator, so the rule is vacuously satisfied).

- [ ] **Step 4: Commit the ArchUnit edit**

```bash
git add src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java \
        src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java
git commit -m "$(cat <<'EOF'
refactor(track-08/wp-01): relax aggregator deny list to permit adapter

Rename aggregatorLayer_shouldNotDependOnNonMappingLayers to
aggregatorLayer_shouldOnlyDependOnAdapterAndMapping and drop ..adapter..
from the deny list. The aggregator legitimately consumes adapter-shaped
output (NormalizedIntegration and IntegrationStatus); the original rule
was overly strict. The remaining deny list still enforces the real
invariants — aggregator must not call back into controller / service /
coordinator. This unblocks the upcoming HealthRollup and projection
record types from reusing the existing IntegrationStatus enum.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: ProjectionRecordsTest — failing tests for IntegrationTypeCount, VendorCard, IntegrationDetail

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/aggregator/ProjectionRecordsTest.java`

This task writes the test scaffolding for the three "leaf" records (no nested records) in one file. The file uses `@Nested` classes so each record's tests are grouped. The test file will not compile yet — that's correct TDD-red.

- [ ] **Step 1: Write the failing test scaffold**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionRecordsTest {

    private static final String VS_ID = "microsoft-defender";
    private static final String VS_NAME = "Microsoft Defender";
    private static final String VENDOR_ID = "microsoft";
    private static final String VENDOR_NAME = "Microsoft";
    private static final VendorCategory VENDOR_CATEGORY = VendorCategory.EDR;
    private static final IntegrationStatus AGGREGATE_HEALTH = IntegrationStatus.HEALTHY;
    private static final Instant LAST_UPDATED = Instant.parse("2026-05-29T10:00:00Z");

    @Nested
    class IntegrationTypeCountTest {

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 1);

            // Assert
            assertThat(record.integrationType()).isEqualTo("SIEM Event Source");
            assertThat(record.total()).isEqualTo(4);
            assertThat(record.errorCount()).isEqualTo(1);
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
            assertNpeFromCtor(
                () -> new IntegrationTypeCount(null, 4, 1),
                IntegrationTypeCount.FIELD_INTEGRATION_TYPE);
        }

        @Test
        void constructor_shouldThrowIAE_whenTotalNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", -1, 0))
                .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
        }

        @Test
        void constructor_shouldThrowIAE_whenErrorCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 4, -1))
                .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT);
        }

        @Test
        void constructor_shouldThrowIAE_whenErrorCountExceedsTotal() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 2, 3))
                .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT)
                .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
        }

        @Test
        void constructor_shouldAccept_whenErrorCountEqualsTotal() {
            // Arrange — boundary case: every instance is in error
            // Act
            IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 4);

            // Assert
            assertThat(record.errorCount()).isEqualTo(4);
        }
    }

    @Nested
    class VendorCardTest {

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            VendorCard record = new VendorCard(VENDOR_ID, VENDOR_NAME, 3);

            // Assert
            assertThat(record.vendorId()).isEqualTo(VENDOR_ID);
            assertThat(record.vendorName()).isEqualTo(VENDOR_NAME);
            assertThat(record.vendorServicesCount()).isEqualTo(3);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorIdNull() {
            assertNpeFromCtor(
                () -> new VendorCard(null, VENDOR_NAME, 3),
                VendorCard.FIELD_VENDOR_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorNameNull() {
            assertNpeFromCtor(
                () -> new VendorCard(VENDOR_ID, null, 3),
                VendorCard.FIELD_VENDOR_NAME);
        }

        @Test
        void constructor_shouldThrowIAE_whenVendorServicesCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VendorCard(VENDOR_ID, VENDOR_NAME, -1))
                .withMessageContaining(VendorCard.FIELD_VENDOR_SERVICES_COUNT);
        }
    }

    @Nested
    class IntegrationDetailTest {

        private static final String INTEGRATION_ID = "es_1234";
        private static final String DATA_SOURCE_ID = "insightidr|product_type|microsoft-defender-endpoint";
        private static final String CONFIGURATION_URL =
            "https://idr.example/eventsources/es_1234";

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, "my-defender",
                IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL);

            // Assert
            assertThat(record.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(record.dataSourceId()).isEqualTo(DATA_SOURCE_ID);
            assertThat(record.integrationLabel()).isEqualTo("my-defender");
            assertThat(record.status()).isEqualTo(IntegrationStatus.HEALTHY);
            assertThat(record.lastSuccessTimestamp()).isEqualTo(LAST_UPDATED);
            assertThat(record.configurationUrl()).isEqualTo(CONFIGURATION_URL);
        }

        @Test
        void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
            // Arrange — RFC-001: integrationLabel is nullable when the source product
            // exposes no per-instance customer-given name (e.g. ICON connections).

            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, null,
                IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL);

            // Assert
            assertThat(record.integrationLabel()).isNull();
        }

        @Test
        void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
            // Act
            IntegrationDetail record = new IntegrationDetail(
                INTEGRATION_ID, DATA_SOURCE_ID, "my-defender",
                IntegrationStatus.HEALTHY, null, CONFIGURATION_URL);

            // Assert
            assertThat(record.lastSuccessTimestamp()).isNull();
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationIdNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(null, DATA_SOURCE_ID, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_INTEGRATION_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenDataSourceIdNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, null, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_DATA_SOURCE_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenStatusNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, DATA_SOURCE_ID, "x",
                    null, LAST_UPDATED, CONFIGURATION_URL),
                IntegrationDetail.FIELD_STATUS);
        }

        @Test
        void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
            assertNpeFromCtor(
                () -> new IntegrationDetail(INTEGRATION_ID, DATA_SOURCE_ID, "x",
                    IntegrationStatus.HEALTHY, LAST_UPDATED, null),
                IntegrationDetail.FIELD_CONFIGURATION_URL);
        }
    }

    private static void assertNpeFromCtor(ThrowingCallable ctor, String expectedField) {
        assertThatNullPointerException().isThrownBy(ctor).withMessage(expectedField);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw test -Dtest=ProjectionRecordsTest -q`
Expected: COMPILATION FAILURE — `cannot find symbol: class IntegrationTypeCount` (and `VendorCard`, `IntegrationDetail`).

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/ProjectionRecordsTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-01): add ProjectionRecordsTest for leaf records

Nested test classes for IntegrationTypeCount (with errorCount<=total
invariant), VendorCard, and IntegrationDetail (two nullable fields).
Production records do not yet exist — the tests will compile after
Task 8 implements them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Implement leaf records — IntegrationTypeCount, VendorCard, IntegrationDetail

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationTypeCount.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorCard.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationDetail.java`

- [ ] **Step 1: Implement `IntegrationTypeCount`**

```java
package com.rapid7.integrationregistry.aggregator;

import java.util.Objects;

/**
 * Per-type aggregation under a vendor service per RFC-001 §Integration Types —
 * one entry per distinct {@code integration_type}. The surface is intentionally
 * narrow: only {@code total} and {@code errorCount}; no {@code warning_count} or
 * other state breakdowns. Adding more counts is a non-breaking forward extension.
 */
public record IntegrationTypeCount(
    String integrationType,
    int total,
    int errorCount
) {

    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_TOTAL = "total";
    static final String FIELD_ERROR_COUNT = "errorCount";

    public IntegrationTypeCount {
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        if (total < 0) {
            throw new IllegalArgumentException(FIELD_TOTAL + " must be >= 0: " + total);
        }
        if (errorCount < 0) {
            throw new IllegalArgumentException(FIELD_ERROR_COUNT + " must be >= 0: " + errorCount);
        }
        if (errorCount > total) {
            throw new IllegalArgumentException(
                FIELD_ERROR_COUNT + " (" + errorCount + ") must be <= "
                    + FIELD_TOTAL + " (" + total + ")");
        }
    }
}
```

- [ ] **Step 2: Implement `VendorCard`**

```java
package com.rapid7.integrationregistry.aggregator;

import java.util.Objects;

/**
 * Lightweight feed for the {@code GET /vendors} filter dropdown — vendor identity
 * plus the count of vendor services with at least one integration in the
 * requesting org. Per RFC-001 §Read API Contract → Projections this projection is
 * intentionally narrow: no {@code aggregate_health}, no {@code vendor_category}.
 */
public record VendorCard(
    String vendorId,
    String vendorName,
    int vendorServicesCount
) {

    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";

    public VendorCard {
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        if (vendorServicesCount < 0) {
            throw new IllegalArgumentException(
                FIELD_VENDOR_SERVICES_COUNT + " must be >= 0: " + vendorServicesCount);
        }
    }
}
```

- [ ] **Step 3: Implement `IntegrationDetail`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-instance detail under a {@link DataSourceDetail} per RFC-001 §Integration
 * entity. {@code integrationLabel} and {@code lastSuccessTimestamp} are
 * nullable per the RFC: {@code integrationLabel} when the source product
 * exposes no per-instance customer-given name, and {@code lastSuccessTimestamp}
 * when no successful activity has ever been recorded.
 */
public record IntegrationDetail(
    String integrationId,
    String dataSourceId,
    String integrationLabel,
    IntegrationStatus status,
    Instant lastSuccessTimestamp,
    String configurationUrl
) {

    static final String FIELD_INTEGRATION_ID = "integrationId";
    static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
    static final String FIELD_STATUS = "status";
    static final String FIELD_CONFIGURATION_URL = "configurationUrl";

    public IntegrationDetail {
        Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
        Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=ProjectionRecordsTest -q`
Expected: BUILD SUCCESS, all `IntegrationTypeCountTest`, `VendorCardTest`, `IntegrationDetailTest` nested tests pass.

- [ ] **Step 5: Commit the implementations**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationTypeCount.java \
        src/main/java/com/rapid7/integrationregistry/aggregator/VendorCard.java \
        src/main/java/com/rapid7/integrationregistry/aggregator/IntegrationDetail.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-01): add leaf projection records

IntegrationTypeCount carries the badge contract — integrationType,
total, errorCount only — with the errorCount<=total invariant
enforced in the compact constructor. VendorCard is the narrow
/vendors filter projection (no aggregate_health, no category, per RFC).
IntegrationDetail is the per-instance shape under DataSourceDetail,
with integrationLabel and lastSuccessTimestamp nullable per RFC.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Composite records — DataSourceDetail, VendorServiceCard, VendorScopedView, VendorServiceDetail

This task combines test scaffolding and implementations for the four remaining records, all of which depend on records from Task 8. We do them in one task because the test file (`ProjectionRecordsTest`) is shared and the composite records have similar shapes — splitting further would multiply tasks without clarity gain.

The TDD discipline: extend the test file FIRST (compile fails), then implement (compile + tests pass).

**Files:**
- Modify: `src/test/java/com/rapid7/integrationregistry/aggregator/ProjectionRecordsTest.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceDetail.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceCard.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorScopedView.java`
- Create: `src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceDetail.java`

- [ ] **Step 1: Extend `ProjectionRecordsTest` with four more `@Nested` classes**

Append the following nested classes inside the existing `ProjectionRecordsTest` body, BEFORE the `assertNpeFromCtor` helper at the bottom. The new classes reuse the top-level test fixtures (`VS_ID`, `VS_NAME`, `VENDOR_ID`, `VENDOR_NAME`, `VENDOR_CATEGORY`, `AGGREGATE_HEALTH`, `LAST_UPDATED`).

```java
    @Nested
    class DataSourceDetailTest {

        private static final String DS_ID = "insightidr|product_type|microsoft-defender-endpoint";
        private static final String DISPLAY_NAME = "Microsoft Defender for Endpoint";
        private static final String INTEGRATION_TYPE = "SIEM Event Source";
        private static final String PRODUCT_NAME = "InsightIDR";

        private IntegrationDetail oneIntegration() {
            return new IntegrationDetail(
                "es_1234", DS_ID, "my-defender",
                IntegrationStatus.HEALTHY, LAST_UPDATED,
                "https://idr.example/eventsources/es_1234");
        }

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Arrange
            List<IntegrationDetail> instances = List.of(oneIntegration());

            // Act
            DataSourceDetail record = new DataSourceDetail(
                DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                IntegrationStatus.HEALTHY, 1, instances);

            // Assert
            assertThat(record.dataSourceId()).isEqualTo(DS_ID);
            assertThat(record.displayName()).isEqualTo(DISPLAY_NAME);
            assertThat(record.integrationType()).isEqualTo(INTEGRATION_TYPE);
            assertThat(record.productName()).isEqualTo(PRODUCT_NAME);
            assertThat(record.status()).isEqualTo(IntegrationStatus.HEALTHY);
            assertThat(record.integrationsCount()).isEqualTo(1);
            assertThat(record.integrations()).hasSize(1);
        }

        @Test
        void constructor_shouldDefensivelyCopyIntegrations() {
            // Arrange — pass a mutable list, then mutate it after construction
            List<IntegrationDetail> mutable = new ArrayList<>();
            mutable.add(oneIntegration());

            // Act
            DataSourceDetail record = new DataSourceDetail(
                DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                IntegrationStatus.HEALTHY, 1, mutable);
            mutable.clear();

            // Assert — record's snapshot is unaffected
            assertThat(record.integrations()).hasSize(1);
        }

        @Test
        void integrationsAccessor_shouldReturnUnmodifiableList() {
            // Arrange
            DataSourceDetail record = new DataSourceDetail(
                DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                IntegrationStatus.HEALTHY, 1, List.of(oneIntegration()));

            // Assert
            assertThatThrownBy(() -> record.integrations().add(oneIntegration()))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void constructor_shouldThrowIAE_whenIntegrationsCountDoesNotEqualListSize() {
            // Arrange — invariant: integrationsCount must equal integrations.size()
            List<IntegrationDetail> instances = List.of(oneIntegration());

            // Assert
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DataSourceDetail(
                    DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, 5, instances))
                .withMessageContaining(DataSourceDetail.FIELD_INTEGRATIONS_COUNT);
        }

        @Test
        void constructor_shouldThrowIAE_whenIntegrationsCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DataSourceDetail(
                    DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, -1, List.of()))
                .withMessageContaining(DataSourceDetail.FIELD_INTEGRATIONS_COUNT);
        }

        @Test
        void constructor_shouldThrowNPE_whenDataSourceIdNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(null, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, 0, List.of()),
                DataSourceDetail.FIELD_DATA_SOURCE_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenDisplayNameNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(DS_ID, null, INTEGRATION_TYPE, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, 0, List.of()),
                DataSourceDetail.FIELD_DISPLAY_NAME);
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(DS_ID, DISPLAY_NAME, null, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, 0, List.of()),
                DataSourceDetail.FIELD_INTEGRATION_TYPE);
        }

        @Test
        void constructor_shouldThrowNPE_whenProductNameNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, null,
                    IntegrationStatus.HEALTHY, 0, List.of()),
                DataSourceDetail.FIELD_PRODUCT_NAME);
        }

        @Test
        void constructor_shouldThrowNPE_whenStatusNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                    null, 0, List.of()),
                DataSourceDetail.FIELD_STATUS);
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationsNull() {
            assertNpeFromCtor(
                () -> new DataSourceDetail(DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME,
                    IntegrationStatus.HEALTHY, 0, null),
                DataSourceDetail.FIELD_INTEGRATIONS);
        }
    }

    @Nested
    class VendorServiceCardTest {

        private VendorServiceCard build(Instant lastUpdated) {
            return new VendorServiceCard(
                VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                4, List.of(new IntegrationTypeCount("SIEM Event Source", 4, 1)),
                List.of("InsightIDR"), AGGREGATE_HEALTH, lastUpdated);
        }

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            VendorServiceCard record = build(LAST_UPDATED);

            // Assert
            assertThat(record.vendorServiceId()).isEqualTo(VS_ID);
            assertThat(record.integrationsConnected()).isEqualTo(4);
            assertThat(record.integrationTypeCounts()).hasSize(1);
            assertThat(record.productsConnected()).containsExactly("InsightIDR");
            assertThat(record.aggregateHealth()).isEqualTo(AGGREGATE_HEALTH);
            assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
        }

        @Test
        void constructor_shouldAcceptNullLastUpdated_whenNoInstanceHasSucceededYet() {
            // Arrange — RFC-001: lastUpdated is nullable

            // Act
            VendorServiceCard record = build(null);

            // Assert
            assertThat(record.lastUpdated()).isNull();
        }

        @Test
        void constructor_shouldDefensivelyCopyCollections() {
            // Arrange
            List<IntegrationTypeCount> mutableCounts = new ArrayList<>();
            mutableCounts.add(new IntegrationTypeCount("SIEM Event Source", 4, 1));
            List<String> mutableProducts = new ArrayList<>();
            mutableProducts.add("InsightIDR");

            // Act
            VendorServiceCard record = new VendorServiceCard(
                VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                4, mutableCounts, mutableProducts, AGGREGATE_HEALTH, LAST_UPDATED);
            mutableCounts.clear();
            mutableProducts.clear();

            // Assert
            assertThat(record.integrationTypeCounts()).hasSize(1);
            assertThat(record.productsConnected()).containsExactly("InsightIDR");
        }

        @Test
        void constructor_shouldThrowIAE_whenIntegrationsConnectedNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    -1, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED))
                .withMessageContaining(VendorServiceCard.FIELD_INTEGRATIONS_CONNECTED);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorServiceIdNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    null, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_VENDOR_SERVICE_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorServiceNameNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, null, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_VENDOR_SERVICE_NAME);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorIdNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, null, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_VENDOR_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorNameNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, null, VENDOR_CATEGORY,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_VENDOR_NAME);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorCategoryNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, null,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_VENDOR_CATEGORY);
        }

        @Test
        void constructor_shouldThrowNPE_whenIntegrationTypeCountsNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, null, List.of(), AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_INTEGRATION_TYPE_COUNTS);
        }

        @Test
        void constructor_shouldThrowNPE_whenProductsConnectedNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), null, AGGREGATE_HEALTH, LAST_UPDATED),
                VendorServiceCard.FIELD_PRODUCTS_CONNECTED);
        }

        @Test
        void constructor_shouldThrowNPE_whenAggregateHealthNull() {
            assertNpeFromCtor(
                () -> new VendorServiceCard(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), List.of(), null, LAST_UPDATED),
                VendorServiceCard.FIELD_AGGREGATE_HEALTH);
        }
    }

    @Nested
    class VendorScopedViewTest {

        private VendorServiceCard oneVendorService() {
            return new VendorServiceCard(
                VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                4, List.of(new IntegrationTypeCount("SIEM Event Source", 4, 1)),
                List.of("InsightIDR"), AGGREGATE_HEALTH, LAST_UPDATED);
        }

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            VendorScopedView record = new VendorScopedView(
                VENDOR_ID, VENDOR_NAME, 1, AGGREGATE_HEALTH, LAST_UPDATED,
                List.of(oneVendorService()));

            // Assert
            assertThat(record.vendorId()).isEqualTo(VENDOR_ID);
            assertThat(record.vendorServicesCount()).isEqualTo(1);
            assertThat(record.aggregateHealth()).isEqualTo(AGGREGATE_HEALTH);
            assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
            assertThat(record.vendorServices()).hasSize(1);
        }

        @Test
        void constructor_shouldAcceptNullLastUpdated_whenNoInstanceHasSucceededYet() {
            // Act
            VendorScopedView record = new VendorScopedView(
                VENDOR_ID, VENDOR_NAME, 1, AGGREGATE_HEALTH, null,
                List.of(oneVendorService()));

            // Assert
            assertThat(record.lastUpdated()).isNull();
        }

        @Test
        void constructor_shouldDefensivelyCopyVendorServices() {
            // Arrange
            List<VendorServiceCard> mutable = new ArrayList<>();
            mutable.add(oneVendorService());

            // Act
            VendorScopedView record = new VendorScopedView(
                VENDOR_ID, VENDOR_NAME, 1, AGGREGATE_HEALTH, LAST_UPDATED, mutable);
            mutable.clear();

            // Assert
            assertThat(record.vendorServices()).hasSize(1);
        }

        @Test
        void constructor_shouldThrowIAE_whenVendorServicesCountNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VendorScopedView(
                    VENDOR_ID, VENDOR_NAME, -1, AGGREGATE_HEALTH, LAST_UPDATED, List.of()))
                .withMessageContaining(VendorScopedView.FIELD_VENDOR_SERVICES_COUNT);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorIdNull() {
            assertNpeFromCtor(
                () -> new VendorScopedView(
                    null, VENDOR_NAME, 0, AGGREGATE_HEALTH, LAST_UPDATED, List.of()),
                VendorScopedView.FIELD_VENDOR_ID);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorNameNull() {
            assertNpeFromCtor(
                () -> new VendorScopedView(
                    VENDOR_ID, null, 0, AGGREGATE_HEALTH, LAST_UPDATED, List.of()),
                VendorScopedView.FIELD_VENDOR_NAME);
        }

        @Test
        void constructor_shouldThrowNPE_whenAggregateHealthNull() {
            assertNpeFromCtor(
                () -> new VendorScopedView(
                    VENDOR_ID, VENDOR_NAME, 0, null, LAST_UPDATED, List.of()),
                VendorScopedView.FIELD_AGGREGATE_HEALTH);
        }

        @Test
        void constructor_shouldThrowNPE_whenVendorServicesNull() {
            assertNpeFromCtor(
                () -> new VendorScopedView(
                    VENDOR_ID, VENDOR_NAME, 0, AGGREGATE_HEALTH, LAST_UPDATED, null),
                VendorScopedView.FIELD_VENDOR_SERVICES);
        }
    }

    @Nested
    class VendorServiceDetailTest {

        private DataSourceDetail oneDataSource() {
            return new DataSourceDetail(
                "insightidr|product_type|microsoft-defender-endpoint",
                "Microsoft Defender for Endpoint",
                "SIEM Event Source",
                "InsightIDR",
                IntegrationStatus.HEALTHY,
                0, List.of());
        }

        private VendorServiceDetail build(Instant lastUpdated) {
            return new VendorServiceDetail(
                VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                0, List.of(), List.of(), AGGREGATE_HEALTH, lastUpdated,
                List.of(oneDataSource()));
        }

        @Test
        void constructor_shouldBuildRecord_whenAllFieldsValid() {
            // Act
            VendorServiceDetail record = build(LAST_UPDATED);

            // Assert
            assertThat(record.vendorServiceId()).isEqualTo(VS_ID);
            assertThat(record.dataSources()).hasSize(1);
            assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
        }

        @Test
        void constructor_shouldAcceptNullLastUpdated() {
            // Act
            VendorServiceDetail record = build(null);

            // Assert
            assertThat(record.lastUpdated()).isNull();
        }

        @Test
        void constructor_shouldDefensivelyCopyDataSources() {
            // Arrange
            List<DataSourceDetail> mutable = new ArrayList<>();
            mutable.add(oneDataSource());

            // Act
            VendorServiceDetail record = new VendorServiceDetail(
                VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED, mutable);
            mutable.clear();

            // Assert
            assertThat(record.dataSources()).hasSize(1);
        }

        @Test
        void constructor_shouldThrowIAE_whenIntegrationsConnectedNegative() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VendorServiceDetail(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    -1, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED, List.of()))
                .withMessageContaining(VendorServiceDetail.FIELD_INTEGRATIONS_CONNECTED);
        }

        @Test
        void constructor_shouldThrowNPE_whenDataSourcesNull() {
            assertNpeFromCtor(
                () -> new VendorServiceDetail(
                    VS_ID, VS_NAME, VENDOR_ID, VENDOR_NAME, VENDOR_CATEGORY,
                    0, List.of(), List.of(), AGGREGATE_HEALTH, LAST_UPDATED, null),
                VendorServiceDetail.FIELD_DATA_SOURCES);
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw test -Dtest=ProjectionRecordsTest -q`
Expected: COMPILATION FAILURE — `cannot find symbol: class DataSourceDetail` (and the other three).

- [ ] **Step 3: Implement `DataSourceDetail`** (must come before `VendorServiceDetail`)

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.util.List;
import java.util.Objects;

/**
 * Per-data-source row for {@code GET /vendor-services/{id}} per RFC-001 §Data
 * Source entity. Carries a nested {@code integrations[]} of per-instance
 * detail. The {@code integrationsCount == integrations.size()} invariant is
 * enforced in the compact constructor — the field exists on the wire because
 * the RFC commits to it, but the two values cannot diverge.
 */
public record DataSourceDetail(
    String dataSourceId,
    String displayName,
    String integrationType,
    String productName,
    IntegrationStatus status,
    int integrationsCount,
    List<IntegrationDetail> integrations
) {

    static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
    static final String FIELD_DISPLAY_NAME = "displayName";
    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_PRODUCT_NAME = "productName";
    static final String FIELD_STATUS = "status";
    static final String FIELD_INTEGRATIONS_COUNT = "integrationsCount";
    static final String FIELD_INTEGRATIONS = "integrations";

    public DataSourceDetail {
        Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
        Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
        if (integrationsCount < 0) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_COUNT + " must be >= 0: " + integrationsCount);
        }
        if (integrationsCount != integrations.size()) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_COUNT + " (" + integrationsCount + ") must equal "
                    + FIELD_INTEGRATIONS + ".size() (" + integrations.size() + ")");
        }
        integrations = List.copyOf(integrations);
    }
}
```

- [ ] **Step 4: Implement `VendorServiceCard`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Primary grid row for {@code GET /vendor-services} per RFC-001 §Vendor Service
 * entity. Embeds {@code vendorId} and {@code vendorName} so the UI can render
 * the vendor filter chip without a separate lookup. {@code lastUpdated} is
 * nullable per the RFC: null when no instance has yet recorded a successful
 * timestamp.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields are dictated by the RFC §Vendor Service entity, not by ergonomics.
public record VendorServiceCard(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    VendorCategory vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCount> integrationTypeCounts,
    List<String> productsConnected,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated
) {

    static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
    static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
    static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
    static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
    static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
    static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

    public VendorServiceCard {
        Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
        Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
        Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
        Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
        Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
        if (integrationsConnected < 0) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
        }
        integrationTypeCounts = List.copyOf(integrationTypeCounts);
        productsConnected = List.copyOf(productsConnected);
    }
}
```

- [ ] **Step 5: Implement `VendorScopedView`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Vendor-scoped view for {@code GET /vendors/{vendor_id}} per RFC-001 §Read API
 * Contract → Projections. Carries the vendor header (with {@code aggregateHealth}
 * and {@code lastUpdated} rolled across this vendor's services) plus a nested
 * list of {@link VendorServiceCard}. {@code lastUpdated} is nullable per the RFC.
 */
public record VendorScopedView(
    String vendorId,
    String vendorName,
    int vendorServicesCount,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated,
    List<VendorServiceCard> vendorServices
) {

    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";
    static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
    static final String FIELD_VENDOR_SERVICES = "vendorServices";

    public VendorScopedView {
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
        Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
        if (vendorServicesCount < 0) {
            throw new IllegalArgumentException(
                FIELD_VENDOR_SERVICES_COUNT + " must be >= 0: " + vendorServicesCount);
        }
        vendorServices = List.copyOf(vendorServices);
    }
}
```

- [ ] **Step 6: Implement `VendorServiceDetail`**

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Expanded row for {@code GET /vendor-services/{vendor_service_id}} per RFC-001
 * §Read API Contract → Projections. Vendor-service header (mirrors
 * {@link VendorServiceCard}) plus {@code dataSources[]} of {@link DataSourceDetail},
 * each carrying its own nested {@code integrations[]}. {@code lastUpdated} is
 * nullable per the RFC.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 11 fields are dictated by the RFC §Vendor Service entity + nested data sources.
public record VendorServiceDetail(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    VendorCategory vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCount> integrationTypeCounts,
    List<String> productsConnected,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated,
    List<DataSourceDetail> dataSources
) {

    static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
    static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
    static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
    static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
    static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
    static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
    static final String FIELD_DATA_SOURCES = "dataSources";

    public VendorServiceDetail {
        Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
        Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
        Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
        Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
        Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
        Objects.requireNonNull(dataSources, FIELD_DATA_SOURCES);
        if (integrationsConnected < 0) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
        }
        integrationTypeCounts = List.copyOf(integrationTypeCounts);
        productsConnected = List.copyOf(productsConnected);
        dataSources = List.copyOf(dataSources);
    }
}
```

- [ ] **Step 7: Run the full ProjectionRecordsTest to verify everything passes**

Run: `./mvnw test -Dtest=ProjectionRecordsTest -q`
Expected: BUILD SUCCESS, all nested-class tests pass.

- [ ] **Step 8: Commit the test extension**

```bash
git add src/test/java/com/rapid7/integrationregistry/aggregator/ProjectionRecordsTest.java
git commit -m "$(cat <<'EOF'
test(track-08/wp-01): extend ProjectionRecordsTest with composite records

Nested test classes for DataSourceDetail (with the
integrationsCount == integrations.size() invariant), VendorServiceCard
(10 fields, two collections), VendorScopedView (nested
vendor-services), and VendorServiceDetail (11 fields, three
collections). Defensive-copy verification on every collection field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9: Commit the implementations**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceDetail.java \
        src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceCard.java \
        src/main/java/com/rapid7/integrationregistry/aggregator/VendorScopedView.java \
        src/main/java/com/rapid7/integrationregistry/aggregator/VendorServiceDetail.java
git commit -m "$(cat <<'EOF'
feat(track-08/wp-01): add composite projection records

DataSourceDetail nests integrations[] and enforces the
integrationsCount == integrations.size() invariant in the compact
constructor. VendorServiceCard and VendorServiceDetail are the full
RFC §Vendor Service entity surface, ten and eleven fields respectively;
each carries a local @SuppressWarnings("PMD.ExcessiveParameterList")
with a one-line justification. VendorScopedView reuses VendorServiceCard
in its nested vendor-services list.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Broaden the package-info Javadoc

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/package-info.java`

- [ ] **Step 1: Replace the file contents**

Before:
```java
/**
 * Vendor-service grouping and worst-state-wins health rollup.
 */
package com.rapid7.integrationregistry.aggregator;
```

After:
```java
/**
 * Vendor-service grouping and worst-state-wins health rollup.
 *
 * <p>Holds the typed surface (projection records) and pure helpers
 * ({@link com.rapid7.integrationregistry.aggregator.HealthRollup},
 * {@link com.rapid7.integrationregistry.aggregator.DataSourceIdMinter}) that the
 * {@code VendorAggregator} composes against and the read-API controller layer
 * serializes from.
 */
package com.rapid7.integrationregistry.aggregator;
```

- [ ] **Step 2: Run a quick compile to verify nothing broke**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/aggregator/package-info.java
git commit -m "$(cat <<'EOF'
docs(track-08/wp-01): broaden aggregator package Javadoc

Extend the existing one-line description with a sentence covering the
new typed surface (projection records + pure helpers) the aggregator
composes against and the read API serializes from.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Final verification — full pipeline green

**Files:** none — verification only.

- [ ] **Step 1: Run the full Maven verify**

Run: `./mvnw verify -q`
Expected: BUILD SUCCESS. All of:
- JUnit (HealthRollupTest, DataSourceIdMinterTest, ProjectionRecordsTest, plus all pre-existing tests)
- ArchUnit (LayerDependencyRulesTest — including the renamed `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping`)
- PMD (no new violations — the two `@SuppressWarnings("PMD.ExcessiveParameterList")` are local and justified)
- Build (jar packaging)

If any check fails, do NOT mark the task complete — debug, fix, and re-run. Common failure modes:
- PMD `AvoidDuplicateLiterals` flagging string literals reused across tests — extract to `static final` constants if so.
- PMD `UnusedImport` — clean up imports in the test file after refactoring.
- ArchUnit failure — typically means an unintended cross-package import slipped in; check the diff.

- [ ] **Step 2: Run `git status` and `git log --oneline` to confirm the branch is clean and all commits are in place**

Run:
```bash
git status
git log --oneline worktree-track-08-wp-01 ^main | head -20
```

Expected: working tree clean, ~10 commits since the branch diverged from main:
- `docs(track-08/wp-01): broaden aggregator package Javadoc`
- `feat(track-08/wp-01): add composite projection records`
- `test(track-08/wp-01): extend ProjectionRecordsTest with composite records`
- `feat(track-08/wp-01): add leaf projection records`
- `test(track-08/wp-01): add ProjectionRecordsTest for leaf records`
- `refactor(track-08/wp-01): relax aggregator deny list to permit adapter`
- `feat(track-08/wp-01): add DataSourceIdMinter for canonical data_source_id`
- `test(track-08/wp-01): add DataSourceIdMinterTest with three RFC vectors`
- `feat(track-08/wp-01): add HealthRollup worst-state-wins precedence function`
- `test(track-08/wp-01): add HealthRollupTest covering 5x5 matrix and NPE`
- `docs(track-08/wp-01): commit aggregator-primitives design spec`

- [ ] **Step 3: STOP. Return control to the parent execute-plan flow.**

DO NOT auto-invoke `superpowers:finishing-a-development-branch`.

Phase 7 (functional review gate), Phase 8 (simplify), and Phase 9 (external code review) run before close-out. The parent flow handles the next steps.

Report completion with: "All 11 tasks complete. `./mvnw verify` green. Branch ready for Phase 7 review gate."
