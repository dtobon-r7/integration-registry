# Vendor Mapping Contract Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Stop after the last task — do NOT auto-invoke `superpowers:finishing-a-development-branch`.** Control returns to the parent `execute-plan` skill for validation gates (functional review, simplify, external code review) before any PR is opened.

**Goal:** Land the stagger-point deliverable for T04 — the bundle JSON Schema (Draft 2020-12) at `src/main/resources/vendor-mapping/schema/v1.json`, the `VendorMappingSnapshot` interface, the `VendorResolution` record (with `unknown()` factory), and three closed enums (`VendorCategory`, `SourceType`, `ProductName`) — so T08 (aggregator) and T11 (CI/publish pipeline) can stagger-start.

**Architecture:** Six new files under `com.rapid7.integrationregistry.mapping`, plus one JSON Schema document under `src/main/resources/vendor-mapping/schema/v1.json`, plus a test-scope dependency on `com.networknt:json-schema-validator:1.5.4`. Types and shapes only, no behavior. The record uses a compact constructor with `Objects.requireNonNull(...)` guards keyed by package-private `FIELD_<NAME>` constants, mirroring the track-05/wp-01 pattern. Each enum carries its RFC-canonical wire form via a constructor argument plus `wireForm()` accessor and a static `fromWireForm(String) → Optional<Enum>` lookup. The schema is closed at every object level (`additionalProperties: false`) and validates the parsed `JsonNode` representation of YAML bundles. No Spring annotations, no JSON serialization annotations, no production-code dependency on the validator library.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven (`./mvnw`), JUnit 5, AssertJ (`assertThat` — already on the test classpath via `spring-boot-starter-test`), `com.networknt:json-schema-validator:1.5.4` (test scope, added in Task 1), ArchUnit + PMD as build gates.

**Pre-flight context for any worker picking this up cold:**
- The branch `uiv/track-04/01-vendor-mapping-contract` is already checked out in the worktree at `repos/platform/integration-registry/.claude/worktrees/track-04-plan-01`. Run all commands from that worktree path.
- The package `com.rapid7.integrationregistry.mapping` already exists and contains only `package-info.java`. Add the new files alongside it.
- The recently-merged track-05/wp-01 work (commit `b468106` is the merge to main) ships the canonical pattern this plan mirrors: records with compact constructors, `Objects.requireNonNull(field, FIELD_<NAME>)` guards, package-private `FIELD_<NAME>` constants referenced by tests via `.withMessage(Type.FIELD_<NAME>)`. Read `src/main/java/com/rapid7/integrationregistry/adapter/SourceIdentifier.java` and `src/test/java/com/rapid7/integrationregistry/adapter/SourceIdentifierTest.java` once before starting if you need the precise shape.
- TESTING.md governs test conventions: unit tests under `src/test/java/com/rapid7/integrationregistry/mapping/`, no Spring context for this plan, JSON fixtures under `src/test/resources/vendor-mapping/`, `methodName_shouldDoX_whenY()` naming, AAA structure with `// Arrange / // Act / // Assert` comments.
- ArchUnit's `aggregatorLayer_shouldNotDependOnNonMappingLayers` already permits the inbound edge from `aggregator` (the only consumer in T08). The other layer rules already exclude `..mapping..`. No new ArchUnit rules are added here.
- PMD rules to remember: `AvoidDuplicateLiterals` (addressed by field-name constants), `CommentContent` (no `TODO|FIXME|HACK|XXX`), `EmptyCatchBlock`, `UnusedLocalVariable`, `UnusedFormalParameter`, `AvoidInstantiatingObjectsInLoops` (the enum `fromWireForm` loops do not allocate, so they are clean). Curated for LLM failure modes — code that compiles but contains placeholders will fail the build.
- Build gate: `./mvnw verify` runs JUnit + ArchUnit + PMD. Each task ends with a focused test run; the final task runs full `verify`.
- Spec lives at `docs/superpowers/specs/2026-05-26-01-vendor-mapping-contract-design.md` if you need to re-check field-by-field details, the full schema document, or the per-test fixture contract.

**Hard non-goals (push back if any task drifts into them):**
- No YAML parser — Plan 02 picks up Jackson `YAMLFactory`.
- No `VendorMappingSnapshot` *implementation* — Plan 02 builds the Map-backed in-memory snapshot.
- No MVP seed bundle — Plan 02 ships the seed YAML.
- No S3 fetch, disk cache, or readiness probe — Plan 03.
- No WARN logging on unknown lookups — Plan 03 (the snapshot impl. logs at runtime; the contract just returns the synthetic value).
- No CI uniqueness, immutability, or deprecation enforcement — T11's bundle CI suite.
- No aggregator, coordinator, controller, or service code.
- No `application.yaml` changes — no `MAPPING_BUNDLE_VERSION`, no S3 config (Plan 03).
- No Spring annotations on contract types — no `@Component`, no `@Service`, no `@ConfigurationProperties`.
- No JSON serialization annotations — no `@JsonProperty`. Wire-form mapping for parsing YAML is Plan 02's; the read-API edge is T09's.
- No new ArchUnit rules.
- No KB doc updates.
- No production-code dependency on `com.networknt:json-schema-validator` — it is **test scope only** in this plan.

---

## File Structure

All new code under `src/main/java/com/rapid7/integrationregistry/mapping/`, `src/main/resources/vendor-mapping/schema/`, `src/test/java/com/rapid7/integrationregistry/mapping/`, and `src/test/resources/vendor-mapping/`. The existing `mapping/package-info.java` is broadened in Task 7 (final task). `pom.xml` gains one test-scope dependency in Task 1.

| File | Responsibility |
|---|---|
| `pom.xml` | Add `com.networknt:json-schema-validator:1.5.4` test-scope dependency |
| `mapping/VendorCategory.java` | Seven-constant enum with wire-form accessor + `fromWireForm` |
| `mapping/SourceType.java` | Four-constant enum with wire-form accessor + `fromWireForm` |
| `mapping/ProductName.java` | Six-constant enum with wire-form accessor + `fromWireForm` |
| `mapping/VendorResolution.java` | Five-field record with `unknown()` factory |
| `resources/vendor-mapping/schema/v1.json` | JSON Schema Draft 2020-12 for the bundle |
| `mapping/VendorMappingSnapshot.java` | Read-side interface |
| `mapping/package-info.java` | Existing — javadoc broadened in Task 7 |

Tests, one per main type that has runtime semantics (the interface itself has none, so no `VendorMappingSnapshotTest`):

| File | Coverage |
|---|---|
| `mapping/VendorCategoryTest.java` | 7 values, wire-form correctness, round-trip, unknown-input |
| `mapping/SourceTypeTest.java` | 4 values, wire-form correctness, round-trip, unknown-input |
| `mapping/ProductNameTest.java` | 6 values, wire-form correctness (incl. two-word `"Surface Command"`), round-trip, unknown-input |
| `mapping/VendorResolutionTest.java` | Happy path + 5 null rejections + unknown() returns synthetic triplet + same-instance |
| `mapping/BundleSchemaTest.java` | 6 positive + 13 negative schema-validation contract tests against fixtures |

JSON fixtures (created in Task 6):

| File | Purpose |
|---|---|
| `resources/vendor-mapping/valid-minimal.json` | One vendor → one service → one data source |
| `resources/vendor-mapping/valid-empty-services.json` | Vendor with `services: []` |
| `resources/vendor-mapping/valid-empty-data-sources.json` | Service with `data_sources: []` |
| `resources/vendor-mapping/valid-no-services-key.json` | Vendor with `services` property omitted entirely |
| `resources/vendor-mapping/valid-unknown-slug.json` | Vendor with `id: "unknown"` — locks schema/CI boundary |
| `resources/vendor-mapping/valid-mapping-version-prerelease.json` | `metadata.mapping_version: "v1.0.0-rc1"` |
| `resources/vendor-mapping/invalid-missing-api-version.json` | Negative case |
| `resources/vendor-mapping/invalid-wrong-api-version.json` | `apiVersion: "registry.rapid7.com/v2"` |
| `resources/vendor-mapping/invalid-wrong-kind.json` | `kind: "VendorMappingV2"` |
| `resources/vendor-mapping/invalid-missing-mapping-version.json` | Negative case |
| `resources/vendor-mapping/invalid-mapping-version-bad-format.json` | `mapping_version: "1.42"` |
| `resources/vendor-mapping/invalid-vendor-slug-uppercase.json` | `vendors[0].id: "Microsoft"` |
| `resources/vendor-mapping/invalid-service-slug-uppercase.json` | `services[0].id: "Microsoft-Defender"` |
| `resources/vendor-mapping/invalid-unknown-category.json` | `category: "foo"` |
| `resources/vendor-mapping/invalid-unknown-product.json` | `product: "MadeUpProduct"` |
| `resources/vendor-mapping/invalid-unknown-source-type.json` | `source_type: "made_up"` |
| `resources/vendor-mapping/invalid-source-value-with-pipe.json` | `source_value: "foo|bar"` |
| `resources/vendor-mapping/invalid-unknown-property.json` | `vendors[0].deprecated_at: "2026-01-01"` — proves `additionalProperties: false` |
| `resources/vendor-mapping/invalid-source-value-empty.json` | `source_value: ""` |

**Task ordering rationale:** dependencies dictate the sequence.

1. **Task 1** — add the test-scope dependency to `pom.xml` first; without it `BundleSchemaTest` (Task 6) won't compile, and the small early commit makes the dependency change reviewable on its own.
2. **Task 2** — `VendorCategory` is needed by `VendorResolution`'s factory (`unknown()` references `VendorCategory.OTHER`).
3. **Task 3** — `SourceType` has no internal dependency.
4. **Task 4** — `ProductName` has no internal dependency.
5. **Task 5** — `VendorResolution` depends on `VendorCategory` (Task 2). Land it before the interface.
6. **Task 6** — `BundleSchemaTest` + JSON Schema + 19 JSON fixtures land together. The schema document and the test that proves it correct are inseparable; a partial commit here would mean either an unverified schema or a test with no schema to run against.
7. **Task 7** — `VendorMappingSnapshot` interface (depends on `VendorResolution`, `ProductName`, `SourceType`) plus the broadened `package-info.java`.
8. **Task 8** — full-build verification checkpoint.

---

## Task 1: Add `json-schema-validator` test-scope dependency

**Files:**
- Modify: `pom.xml`

**Why this is its own commit:** dependency changes are reviewable on their own. Bundling them with code creates noisy diffs and makes future bisection harder.

- [ ] **Step 1: Read the current `pom.xml` `<dependencies>` block**

Run: `./mvnw -q help:effective-pom -Doutput=/dev/stdout 2>&1 | grep -B1 -A3 'json-schema-validator' || echo "not present"`
Expected: prints `not present` — confirms the dependency isn't already there.

- [ ] **Step 2: Add the dependency**

Modify `pom.xml`. Find the existing `<dependencies>` block (line ~20–47 in the current file) and add this entry after the `archunit-junit5` dependency, before the closing `</dependencies>` tag:

```xml
		<dependency>
			<groupId>com.networknt</groupId>
			<artifactId>json-schema-validator</artifactId>
			<version>1.5.4</version>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 3: Verify the dependency resolves**

Run: `./mvnw -q dependency:resolve -DincludeScope=test 2>&1 | grep 'json-schema-validator'`
Expected: a line including `com.networknt:json-schema-validator:jar:1.5.4:test` — confirms Maven downloaded it.

- [ ] **Step 4: Verify the existing build still passes**

Run: `./mvnw -q compile test-compile`
Expected: `BUILD SUCCESS`. No production code or test code change yet, so this is a sanity check that the dependency addition didn't break the build.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build(track-04/wp-01): add networknt json-schema-validator (test scope)"
```

---

## Task 2: `VendorCategory` enum

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/VendorCategoryTest.java`

**Why this comes before `VendorResolution`:** `VendorResolution.unknown()` references `VendorCategory.OTHER`. The compiler resolves that reference at Task 5; `VendorCategory` must exist first.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/VendorCategoryTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VendorCategoryTest {

    @Test
    void values_shouldContainExactlySevenCategories_whenInspected() {
        // Act
        VendorCategory[] all = VendorCategory.values();

        // Assert
        assertThat(all).containsExactly(
            VendorCategory.CLOUD_PROVIDER,
            VendorCategory.IDENTITY,
            VendorCategory.ITSM,
            VendorCategory.SIEM,
            VendorCategory.EDR,
            VendorCategory.NOTIFICATION,
            VendorCategory.OTHER
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert
        assertThat(VendorCategory.CLOUD_PROVIDER.wireForm()).isEqualTo("cloud_provider");
        assertThat(VendorCategory.IDENTITY.wireForm()).isEqualTo("identity");
        assertThat(VendorCategory.ITSM.wireForm()).isEqualTo("itsm");
        assertThat(VendorCategory.SIEM.wireForm()).isEqualTo("siem");
        assertThat(VendorCategory.EDR.wireForm()).isEqualTo("edr");
        assertThat(VendorCategory.NOTIFICATION.wireForm()).isEqualTo("notification");
        assertThat(VendorCategory.OTHER.wireForm()).isEqualTo("other");
    }

    @Test
    void fromWireForm_shouldResolveAllSevenConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(VendorCategory.fromWireForm("cloud_provider")).contains(VendorCategory.CLOUD_PROVIDER);
        assertThat(VendorCategory.fromWireForm("identity")).contains(VendorCategory.IDENTITY);
        assertThat(VendorCategory.fromWireForm("itsm")).contains(VendorCategory.ITSM);
        assertThat(VendorCategory.fromWireForm("siem")).contains(VendorCategory.SIEM);
        assertThat(VendorCategory.fromWireForm("edr")).contains(VendorCategory.EDR);
        assertThat(VendorCategory.fromWireForm("notification")).contains(VendorCategory.NOTIFICATION);
        assertThat(VendorCategory.fromWireForm("other")).contains(VendorCategory.OTHER);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<VendorCategory> result = VendorCategory.fromWireForm("not-a-real-category");

        // Assert
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=VendorCategoryTest`
Expected: FAIL with compilation error (`VendorCategory` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum VendorCategory {
    CLOUD_PROVIDER("cloud_provider"),
    IDENTITY("identity"),
    ITSM("itsm"),
    SIEM("siem"),
    EDR("edr"),
    NOTIFICATION("notification"),
    OTHER("other");

    private final String wireForm;

    VendorCategory(String wireForm) {
        this.wireForm = wireForm;
    }

    public String wireForm() {
        return wireForm;
    }

    public static Optional<VendorCategory> fromWireForm(String wireForm) {
        for (VendorCategory category : values()) {
            if (category.wireForm.equals(wireForm)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=VendorCategoryTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java src/test/java/com/rapid7/integrationregistry/mapping/VendorCategoryTest.java
git commit -m "feat(track-04/wp-01): add VendorCategory enum with wire-form round-trip"
```

---

## Task 3: `SourceType` enum

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/SourceType.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/SourceTypeTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/SourceTypeTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeTest {

    @Test
    void values_shouldContainExactlyFourSourceTypes_whenInspected() {
        // Act
        SourceType[] all = SourceType.values();

        // Assert
        assertThat(all).containsExactly(
            SourceType.PLUGIN_NAME,
            SourceType.PRODUCT_TYPE,
            SourceType.PRODUCT_NAME,
            SourceType.INTEGRATION_ID
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert
        assertThat(SourceType.PLUGIN_NAME.wireForm()).isEqualTo("plugin_name");
        assertThat(SourceType.PRODUCT_TYPE.wireForm()).isEqualTo("product_type");
        assertThat(SourceType.PRODUCT_NAME.wireForm()).isEqualTo("product_name");
        assertThat(SourceType.INTEGRATION_ID.wireForm()).isEqualTo("integration_id");
    }

    @Test
    void fromWireForm_shouldResolveAllFourConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(SourceType.fromWireForm("plugin_name")).contains(SourceType.PLUGIN_NAME);
        assertThat(SourceType.fromWireForm("product_type")).contains(SourceType.PRODUCT_TYPE);
        assertThat(SourceType.fromWireForm("product_name")).contains(SourceType.PRODUCT_NAME);
        assertThat(SourceType.fromWireForm("integration_id")).contains(SourceType.INTEGRATION_ID);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<SourceType> result = SourceType.fromWireForm("not-a-real-source-type");

        // Assert
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SourceTypeTest`
Expected: FAIL with compilation error (`SourceType` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/SourceType.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum SourceType {
    PLUGIN_NAME("plugin_name"),
    PRODUCT_TYPE("product_type"),
    PRODUCT_NAME("product_name"),
    INTEGRATION_ID("integration_id");

    private final String wireForm;

    SourceType(String wireForm) {
        this.wireForm = wireForm;
    }

    public String wireForm() {
        return wireForm;
    }

    public static Optional<SourceType> fromWireForm(String wireForm) {
        for (SourceType type : values()) {
            if (type.wireForm.equals(wireForm)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SourceTypeTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/SourceType.java src/test/java/com/rapid7/integrationregistry/mapping/SourceTypeTest.java
git commit -m "feat(track-04/wp-01): add SourceType enum with wire-form round-trip"
```

---

## Task 4: `ProductName` enum

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/ProductName.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/ProductNameTest.java`

**Special note:** `SURFACE_COMMAND` carries the **two-word** wire form `"Surface Command"` (with a space) per RFC-001 §Canonical `productName()` values. The test asserts this exactly to guard against the easy-to-make `"SurfaceCommand"` typo.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/ProductNameTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProductNameTest {

    @Test
    void values_shouldContainExactlySixProducts_whenInspected() {
        // Act
        ProductName[] all = ProductName.values();

        // Assert
        assertThat(all).containsExactly(
            ProductName.INSIGHT_IDR,
            ProductName.INSIGHT_CONNECT,
            ProductName.SURFACE_COMMAND,
            ProductName.INSIGHT_VM,
            ProductName.INSIGHT_CLOUD_SEC,
            ProductName.INSIGHT_APP_SEC
        );
    }

    @Test
    void wireForm_shouldReturnRfcCanonicalString_forEachConstant() {
        // Assert — note the deliberate two-word form for Surface Command (with a space)
        assertThat(ProductName.INSIGHT_IDR.wireForm()).isEqualTo("InsightIDR");
        assertThat(ProductName.INSIGHT_CONNECT.wireForm()).isEqualTo("InsightConnect");
        assertThat(ProductName.SURFACE_COMMAND.wireForm()).isEqualTo("Surface Command");
        assertThat(ProductName.INSIGHT_VM.wireForm()).isEqualTo("InsightVM");
        assertThat(ProductName.INSIGHT_CLOUD_SEC.wireForm()).isEqualTo("InsightCloudSec");
        assertThat(ProductName.INSIGHT_APP_SEC.wireForm()).isEqualTo("InsightAppSec");
    }

    @Test
    void fromWireForm_shouldResolveAllSixConstants_whenLookedUpByWireForm() {
        // Assert
        assertThat(ProductName.fromWireForm("InsightIDR")).contains(ProductName.INSIGHT_IDR);
        assertThat(ProductName.fromWireForm("InsightConnect")).contains(ProductName.INSIGHT_CONNECT);
        assertThat(ProductName.fromWireForm("Surface Command")).contains(ProductName.SURFACE_COMMAND);
        assertThat(ProductName.fromWireForm("InsightVM")).contains(ProductName.INSIGHT_VM);
        assertThat(ProductName.fromWireForm("InsightCloudSec")).contains(ProductName.INSIGHT_CLOUD_SEC);
        assertThat(ProductName.fromWireForm("InsightAppSec")).contains(ProductName.INSIGHT_APP_SEC);
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenWireFormUnknown() {
        // Act
        Optional<ProductName> result = ProductName.fromWireForm("MadeUpProduct");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void fromWireForm_shouldReturnEmpty_whenSurfaceCommandSpaceIsMissing() {
        // Arrange
        // Guards against the easy-to-make "SurfaceCommand" (no space) typo —
        // RFC-001 is explicit that the wire form is "Surface Command".

        // Act
        Optional<ProductName> result = ProductName.fromWireForm("SurfaceCommand");

        // Assert
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ProductNameTest`
Expected: FAIL with compilation error (`ProductName` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/ProductName.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum ProductName {
    INSIGHT_IDR("InsightIDR"),
    INSIGHT_CONNECT("InsightConnect"),
    SURFACE_COMMAND("Surface Command"),
    INSIGHT_VM("InsightVM"),
    INSIGHT_CLOUD_SEC("InsightCloudSec"),
    INSIGHT_APP_SEC("InsightAppSec");

    private final String wireForm;

    ProductName(String wireForm) {
        this.wireForm = wireForm;
    }

    public String wireForm() {
        return wireForm;
    }

    public static Optional<ProductName> fromWireForm(String wireForm) {
        for (ProductName product : values()) {
            if (product.wireForm.equals(wireForm)) {
                return Optional.of(product);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ProductNameTest`
Expected: PASS — all five tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/ProductName.java src/test/java/com/rapid7/integrationregistry/mapping/ProductNameTest.java
git commit -m "feat(track-04/wp-01): add ProductName enum (incl. two-word Surface Command)"
```

---

## Task 5: `VendorResolution` record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/VendorResolutionTest.java`

**Why this comes after the three enums:** the `unknown()` factory references `VendorCategory.OTHER`. Records that carry an enum field need that enum to be on the classpath at compile time.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/VendorResolutionTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorResolutionTest {

    private static final String VENDOR_SERVICE_ID = "microsoft-defender";
    private static final String VENDOR_SERVICE_NAME = "Microsoft Defender";
    private static final VendorCategory VENDOR_CATEGORY = VendorCategory.EDR;
    private static final String VENDOR_ID = "microsoft";
    private static final String VENDOR_NAME = "Microsoft";

    @Test
    void constructor_shouldBuildRecord_whenAllFiveFieldsProvided() {
        // Act
        VendorResolution resolution = new VendorResolution(
            VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME);

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo(VENDOR_SERVICE_ID);
        assertThat(resolution.vendorServiceName()).isEqualTo(VENDOR_SERVICE_NAME);
        assertThat(resolution.vendorCategory()).isEqualTo(VENDOR_CATEGORY);
        assertThat(resolution.vendorId()).isEqualTo(VENDOR_ID);
        assertThat(resolution.vendorName()).isEqualTo(VENDOR_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                null, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_SERVICE_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, null, VENDOR_CATEGORY, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_SERVICE_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorCategoryNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, null, VENDOR_ID, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_CATEGORY);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, null, VENDOR_NAME))
            .withMessage(VendorResolution.FIELD_VENDOR_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorResolution(
                VENDOR_SERVICE_ID, VENDOR_SERVICE_NAME, VENDOR_CATEGORY, VENDOR_ID, null))
            .withMessage(VendorResolution.FIELD_VENDOR_NAME);
    }

    @Test
    void unknown_shouldReturnSyntheticTriplet_whenInvoked() {
        // Act
        VendorResolution unknown = VendorResolution.unknown();

        // Assert
        assertThat(unknown.vendorServiceId()).isEqualTo("unknown");
        assertThat(unknown.vendorServiceName()).isEqualTo("Unknown");
        assertThat(unknown.vendorCategory()).isEqualTo(VendorCategory.OTHER);
        assertThat(unknown.vendorId()).isEqualTo("unknown");
        assertThat(unknown.vendorName()).isEqualTo("Unknown");
    }

    @Test
    void unknown_shouldReturnSameInstance_whenInvokedTwice() {
        // Act
        VendorResolution first = VendorResolution.unknown();
        VendorResolution second = VendorResolution.unknown();

        // Assert
        assertThat(first).isSameAs(second);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=VendorResolutionTest`
Expected: FAIL with compilation error (`VendorResolution` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Objects;

public record VendorResolution(
    String vendorServiceId,
    String vendorServiceName,
    VendorCategory vendorCategory,
    String vendorId,
    String vendorName
) {

    static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
    static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
    static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";

    private static final VendorResolution UNKNOWN = new VendorResolution(
        "unknown", "Unknown", VendorCategory.OTHER, "unknown", "Unknown"
    );

    public VendorResolution {
        Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
        Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
        Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    }

    public static VendorResolution unknown() {
        return UNKNOWN;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=VendorResolutionTest`
Expected: PASS — all eight tests green.

**PMD note:** PMD's `MutableStaticState` rule may inspect the `private static final UNKNOWN` field. The field is `final` and the type is a record (deeply immutable: all five components are either `String` or an enum, both immutable). PMD's rule targets mutable static state; this is immutable static state and will not fire.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java src/test/java/com/rapid7/integrationregistry/mapping/VendorResolutionTest.java
git commit -m "feat(track-04/wp-01): add VendorResolution record with unknown() factory"
```

---

## Task 6: JSON Schema document and contract tests

**Files:**
- Create: `src/main/resources/vendor-mapping/schema/v1.json`
- Create: `src/test/java/com/rapid7/integrationregistry/mapping/BundleSchemaTest.java`
- Create: 19 fixture files under `src/test/resources/vendor-mapping/`

**Why everything in this task lands together:** the schema document and the test that proves it correct are inseparable. A partial commit means either a schema with no test running against it, or a test asserting nothing.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/BundleSchemaTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BundleSchemaTest {

    private static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
    private static final String FIXTURES_ROOT = "/vendor-mapping/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = BundleSchemaTest.class.getResourceAsStream(SCHEMA_CLASSPATH)) {
            assertThat(in)
                .as("schema resource %s present on classpath", SCHEMA_CLASSPATH)
                .isNotNull();
            JsonNode schemaNode = MAPPER.readTree(in);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            schema = factory.getSchema(schemaNode);
        }
    }

    private Set<ValidationMessage> validateFixture(String fixtureFileName) throws IOException {
        String classpathPath = FIXTURES_ROOT + fixtureFileName;
        try (InputStream in = BundleSchemaTest.class.getResourceAsStream(classpathPath)) {
            assertThat(in)
                .as("fixture resource %s present on classpath", classpathPath)
                .isNotNull();
            JsonNode document = MAPPER.readTree(in);
            return schema.validate(document);
        }
    }

    // ---------- Positive cases ----------

    @Test
    void validate_shouldAccept_whenBundleIsMinimalValid() throws IOException {
        Set<ValidationMessage> errors = validateFixture("valid-minimal.json");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_shouldAccept_whenServicesArrayIsEmpty() throws IOException {
        Set<ValidationMessage> errors = validateFixture("valid-empty-services.json");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_shouldAccept_whenDataSourcesArrayIsEmpty() throws IOException {
        Set<ValidationMessage> errors = validateFixture("valid-empty-data-sources.json");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_shouldAccept_whenServicesKeyOmitted() throws IOException {
        Set<ValidationMessage> errors = validateFixture("valid-no-services-key.json");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_shouldAccept_whenSlugIsLiteralUnknown() throws IOException {
        // The schema permits "unknown" as a slug because it matches ^[a-z0-9_-]+$.
        // T11's CI suite is the enforcement boundary for the reservation — not the schema.
        Set<ValidationMessage> errors = validateFixture("valid-unknown-slug.json");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_shouldAccept_whenMappingVersionHasPreReleaseSuffix() throws IOException {
        Set<ValidationMessage> errors = validateFixture("valid-mapping-version-prerelease.json");
        assertThat(errors).isEmpty();
    }

    // ---------- Negative cases ----------

    @Test
    void validate_shouldReject_whenApiVersionMissing() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-missing-api-version.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getMessage().contains("apiVersion"));
    }

    @Test
    void validate_shouldReject_whenApiVersionIsWrongValue() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-wrong-api-version.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/apiVersion"));
    }

    @Test
    void validate_shouldReject_whenKindIsWrongValue() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-wrong-kind.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/kind"));
    }

    @Test
    void validate_shouldReject_whenMappingVersionMissing() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-missing-mapping-version.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getMessage().contains("mapping_version"));
    }

    @Test
    void validate_shouldReject_whenMappingVersionIsNotSemver() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-mapping-version-bad-format.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/metadata/mapping_version"));
    }

    @Test
    void validate_shouldReject_whenVendorSlugFailsRegex() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-vendor-slug-uppercase.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/spec/vendors/0/id"));
    }

    @Test
    void validate_shouldReject_whenServiceSlugFailsRegex() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-service-slug-uppercase.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/services/0/id"));
    }

    @Test
    void validate_shouldReject_whenCategoryNotInEnum() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-unknown-category.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/category"));
    }

    @Test
    void validate_shouldReject_whenProductNotInEnum() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-unknown-product.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/product"));
    }

    @Test
    void validate_shouldReject_whenSourceTypeNotInEnum() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-unknown-source-type.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/source_type"));
    }

    @Test
    void validate_shouldReject_whenSourceValueContainsPipe() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-source-value-with-pipe.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/source_value"));
    }

    @Test
    void validate_shouldReject_whenUnknownPropertyOnVendor() throws IOException {
        // Proves additionalProperties: false is in effect — typos like `data_soruces`
        // or fabricated fields like `deprecated_at` fail validation.
        Set<ValidationMessage> errors = validateFixture("invalid-unknown-property.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getMessage().contains("deprecated_at"));
    }

    @Test
    void validate_shouldReject_whenSourceValueIsEmpty() throws IOException {
        Set<ValidationMessage> errors = validateFixture("invalid-source-value-empty.json");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains("/source_value"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BundleSchemaTest`
Expected: FAIL — `BeforeAll` cannot find the schema resource (`SCHEMA_CLASSPATH` = `/vendor-mapping/schema/v1.json` does not exist yet). Every test fails on the missing fixture or schema.

- [ ] **Step 3: Create the JSON Schema document**

Create `src/main/resources/vendor-mapping/schema/v1.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://registry.rapid7.com/schemas/vendor-mapping/v1.json",
  "title": "VendorMapping bundle (registry.rapid7.com/v1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["apiVersion", "kind", "metadata", "spec"],
  "properties": {
    "apiVersion": { "const": "registry.rapid7.com/v1" },
    "kind":       { "const": "VendorMapping" },
    "metadata":   { "$ref": "#/$defs/Metadata" },
    "spec":       { "$ref": "#/$defs/Spec" }
  },
  "$defs": {
    "Slug": {
      "type": "string",
      "pattern": "^[a-z0-9_-]+$"
    },
    "SemverString": {
      "type": "string",
      "pattern": "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[a-zA-Z0-9.-]+)?(?:\\+[a-zA-Z0-9.-]+)?$"
    },
    "SourceValue": {
      "type": "string",
      "minLength": 1,
      "not": { "pattern": "\\|" }
    },
    "Metadata": {
      "type": "object",
      "additionalProperties": false,
      "required": ["mapping_version"],
      "properties": {
        "mapping_version": { "$ref": "#/$defs/SemverString" }
      }
    },
    "Spec": {
      "type": "object",
      "additionalProperties": false,
      "required": ["vendors"],
      "properties": {
        "vendors": { "type": "array", "items": { "$ref": "#/$defs/Vendor" } }
      }
    },
    "Vendor": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "name"],
      "properties": {
        "id":       { "$ref": "#/$defs/Slug" },
        "name":     { "type": "string", "minLength": 1 },
        "services": { "type": "array", "items": { "$ref": "#/$defs/VendorService" } }
      }
    },
    "VendorService": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "name", "category"],
      "properties": {
        "id":           { "$ref": "#/$defs/Slug" },
        "name":         { "type": "string", "minLength": 1 },
        "category":     { "enum": ["cloud_provider","identity","itsm","siem","edr","notification","other"] },
        "data_sources": { "type": "array", "items": { "$ref": "#/$defs/DataSource" } }
      }
    },
    "DataSource": {
      "type": "object",
      "additionalProperties": false,
      "required": ["product", "source_type", "source_value", "display_name"],
      "properties": {
        "product":      { "enum": ["InsightIDR","InsightConnect","Surface Command","InsightVM","InsightCloudSec","InsightAppSec"] },
        "source_type":  { "enum": ["plugin_name","product_type","product_name","integration_id"] },
        "source_value": { "$ref": "#/$defs/SourceValue" },
        "display_name": { "type": "string", "minLength": 1 }
      }
    }
  }
}
```

- [ ] **Step 4: Create the six positive-case fixtures**

Create `src/test/resources/vendor-mapping/valid-minimal.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          {
            "id": "microsoft-defender",
            "name": "Microsoft Defender",
            "category": "edr",
            "data_sources": [
              {
                "product": "InsightIDR",
                "source_type": "product_type",
                "source_value": "microsoft-defender-endpoint",
                "display_name": "Microsoft Defender for Endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/valid-empty-services.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      { "id": "microsoft", "name": "Microsoft", "services": [] }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/valid-empty-data-sources.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          { "id": "microsoft-sentinel", "name": "Microsoft Sentinel", "category": "siem", "data_sources": [] }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/valid-no-services-key.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      { "id": "microsoft", "name": "Microsoft" }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/valid-unknown-slug.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      { "id": "unknown", "name": "Unknown", "services": [] }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/valid-mapping-version-prerelease.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0-rc1" },
  "spec": {
    "vendors": [
      { "id": "microsoft", "name": "Microsoft", "services": [] }
    ]
  }
}
```

- [ ] **Step 5: Create the thirteen negative-case fixtures**

Create `src/test/resources/vendor-mapping/invalid-missing-api-version.json`:

```json
{
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": { "vendors": [] }
}
```

Create `src/test/resources/vendor-mapping/invalid-wrong-api-version.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v2",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": { "vendors": [] }
}
```

Create `src/test/resources/vendor-mapping/invalid-wrong-kind.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMappingV2",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": { "vendors": [] }
}
```

Create `src/test/resources/vendor-mapping/invalid-missing-mapping-version.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": {},
  "spec": { "vendors": [] }
}
```

Create `src/test/resources/vendor-mapping/invalid-mapping-version-bad-format.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "1.42" },
  "spec": { "vendors": [] }
}
```

Create `src/test/resources/vendor-mapping/invalid-vendor-slug-uppercase.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      { "id": "Microsoft", "name": "Microsoft", "services": [] }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-service-slug-uppercase.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          { "id": "Microsoft-Defender", "name": "Microsoft Defender", "category": "edr", "data_sources": [] }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-unknown-category.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          { "id": "microsoft-defender", "name": "Microsoft Defender", "category": "foo", "data_sources": [] }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-unknown-product.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          {
            "id": "microsoft-defender",
            "name": "Microsoft Defender",
            "category": "edr",
            "data_sources": [
              {
                "product": "MadeUpProduct",
                "source_type": "product_type",
                "source_value": "microsoft-defender-endpoint",
                "display_name": "Microsoft Defender for Endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-unknown-source-type.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          {
            "id": "microsoft-defender",
            "name": "Microsoft Defender",
            "category": "edr",
            "data_sources": [
              {
                "product": "InsightIDR",
                "source_type": "made_up",
                "source_value": "microsoft-defender-endpoint",
                "display_name": "Microsoft Defender for Endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-source-value-with-pipe.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          {
            "id": "microsoft-defender",
            "name": "Microsoft Defender",
            "category": "edr",
            "data_sources": [
              {
                "product": "InsightIDR",
                "source_type": "product_type",
                "source_value": "foo|bar",
                "display_name": "Microsoft Defender for Endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-unknown-property.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "deprecated_at": "2026-01-01",
        "services": []
      }
    ]
  }
}
```

Create `src/test/resources/vendor-mapping/invalid-source-value-empty.json`:

```json
{
  "apiVersion": "registry.rapid7.com/v1",
  "kind": "VendorMapping",
  "metadata": { "mapping_version": "v1.0.0" },
  "spec": {
    "vendors": [
      {
        "id": "microsoft",
        "name": "Microsoft",
        "services": [
          {
            "id": "microsoft-defender",
            "name": "Microsoft Defender",
            "category": "edr",
            "data_sources": [
              {
                "product": "InsightIDR",
                "source_type": "product_type",
                "source_value": "",
                "display_name": "Microsoft Defender for Endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=BundleSchemaTest`
Expected: PASS — all 19 tests green (6 positive + 13 negative).

If any negative test fails because the assertion's `getInstanceLocation()` substring doesn't match, the validator's location-string format may differ slightly from the JSON Pointer the assertion expects. Adjust the substring to match the actual location string returned (run with `-X` to see the validation messages emitted), but do not weaken the test to "errors not empty" alone — the negative tests must prove the validator caught the **right** rule.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/vendor-mapping/schema/v1.json src/test/java/com/rapid7/integrationregistry/mapping/BundleSchemaTest.java src/test/resources/vendor-mapping/
git commit -m "feat(track-04/wp-01): publish bundle JSON Schema v1 with 19 contract tests"
```

---

## Task 7: `VendorMappingSnapshot` interface and broadened `package-info`

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java`
- Modify: `src/main/java/com/rapid7/integrationregistry/mapping/package-info.java`

**Why no test for the interface:** the interface has no runtime semantics — it only declares two method signatures. Compilation IS the test (the package compiles, all referenced types resolve, ArchUnit allows the dependencies). A dedicated test would have to either implement the interface (defeating the purpose of "no behavior in this PR") or use reflection to assert signature shape (testing the compiler). Skipped deliberately, mirroring track-05/wp-01's decision on `IntegrationAdapter`.

- [ ] **Step 1: Write the interface**

Create `src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java`:

```java
package com.rapid7.integrationregistry.mapping;

public interface VendorMappingSnapshot {

    /**
     * Resolve a raw product source identifier to its canonical vendor / vendor-service
     * identity.
     *
     * @return resolution result for known triplets, or {@link VendorResolution#unknown()}
     *     for unmapped triplets — never null, never throws on unmapped input.
     */
    VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue);

    /**
     * The {@code metadata.mapping_version} of the loaded bundle (semver string,
     * e.g. {@code "v1.42.0"}). Surfaced on every API response by T09.
     */
    String mappingVersion();
}
```

- [ ] **Step 2: Broaden the `package-info` javadoc**

Replace the contents of `src/main/java/com/rapid7/integrationregistry/mapping/package-info.java` with:

```java
/**
 * Vendor-mapping read-side contract: the {@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot}
 * interface, the {@link com.rapid7.integrationregistry.mapping.VendorResolution} record carrying resolved
 * vendor/vendor-service identity, and the closed enums
 * ({@link com.rapid7.integrationregistry.mapping.VendorCategory},
 * {@link com.rapid7.integrationregistry.mapping.SourceType},
 * {@link com.rapid7.integrationregistry.mapping.ProductName}) referenced by the bundle JSON Schema and
 * by the snapshot lookup API.
 *
 * <p>Implementations of {@code VendorMappingSnapshot} live in this package (Plan 02);
 * no other internal Registry layer may depend on this package other than {@code aggregator}
 * (enforced by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
```

- [ ] **Step 3: Verify the package compiles and ArchUnit still passes**

Run: `./mvnw test -Dtest=LayerDependencyRulesTest`
Expected: PASS — `aggregatorLayer_shouldNotDependOnNonMappingLayers` continues to pass; the new types in `mapping` are not consumed by any other internal layer in this PR.

- [ ] **Step 4: Run all mapping-package tests to confirm nothing regressed**

Run: `./mvnw test -Dtest='VendorCategoryTest,SourceTypeTest,ProductNameTest,VendorResolutionTest,BundleSchemaTest,LayerDependencyRulesTest'`
Expected: PASS — every test from Tasks 2–6 plus the architecture rules.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java src/main/java/com/rapid7/integrationregistry/mapping/package-info.java
git commit -m "feat(track-04/wp-01): add VendorMappingSnapshot interface; broaden package javadoc"
```

---

## Task 8: Full-build verification checkpoint

**Files:** none — pure verification.

This is the gate that proves the work plan's Acceptance signals: every test green, ArchUnit green, PMD green.

- [ ] **Step 1: Run full verify**

Run: `./mvnw verify`
Expected:
- `BUILD SUCCESS`
- All JUnit tests pass: the five new test classes from Tasks 2–6 (`VendorCategoryTest`, `SourceTypeTest`, `ProductNameTest`, `VendorResolutionTest`, `BundleSchemaTest`) plus the existing tests (`LayerDependencyRulesTest`, `LayerDependencyViolationDetectionTest`, `IntegrationRegistryApplicationTests`, the five `adapter/` tests from track-05/wp-01).
- ArchUnit reports zero violations.
- PMD reports zero violations across both `src/main/java` and `src/test/java`.

- [ ] **Step 2: If `./mvnw verify` fails, diagnose and fix**

Common failure modes specific to this plan:
- **PMD `AvoidDuplicateLiterals`** — only fires on a literal repeated 4+ times in the same file. The `FIELD_<NAME>` constants prevent this on `VendorResolution`. The test fixtures live in JSON files (PMD doesn't scan them). The string literals in test classes (e.g., `"InsightIDR"` in `ProductNameTest.wireForm_…`) appear at most twice each — under the threshold.
- **PMD `MutableStaticState`** on `VendorResolution.UNKNOWN` — the field is `final` and the type is a record with only `String` and enum components (deeply immutable). Should not fire. If it does, mark the suppression `@SuppressWarnings("PMD.MutableStaticState")` only after confirming the field is genuinely immutable; bury the justification in a comment ending in a period.
- **PMD `CommentContent`** — would fire if any comment contains `TODO|FIXME|HACK|XXX`. Remove any such comment; if you genuinely need to mark deferred work, capture it as a follow-up work plan via the `work-plans` skill instead.
- **PMD `AvoidInstantiatingObjectsInLoops`** on the enum `fromWireForm` loops — should not fire (the loop body returns an `Optional` from `Optional.of(category)` only on the matching iteration, no per-iteration allocation in the failure path; iterating `values()` does not allocate per element). If it does fire, the rule has been tightened locally — the loop is correct.
- **ArchUnit failure** — if a layer rule flags the new types, you imported a Spring or other-internal-package type. The mapping types should depend only on the JDK (`java.util.Objects`, `java.util.Optional`).
- **`BundleSchemaTest` validator-message format mismatch** — if a negative test fails on the location-substring assertion, the validator's location string format may differ (e.g., `$.spec.vendors[0].id` vs `/spec/vendors/0/id`). Run the failing test with `-X` (Maven debug) to see the actual `getInstanceLocation()` and `getMessage()` values; adjust the substring to match. Do not remove the substring assertion — keep it specific so the test proves the right rule fired.

Diagnose, fix, re-run. Do not bypass the gate.

- [ ] **Step 3: Confirm git state**

```bash
git status
git log --oneline main..HEAD
```

Expected:
- `git status` reports a clean working tree (no uncommitted changes).
- `git log` shows commits matching:
  - The spec commit (`docs(track-04/wp-01): design spec…`) from the brainstorming step (commit `fcbe5d2` on this branch).
  - Seven feature commits, one per Task 1–7 (Task 1 = dependency, Tasks 2/3/4 = three enums, Task 5 = `VendorResolution`, Task 6 = schema + 19 fixtures + tests, Task 7 = interface + package-info).
  - Total: 8 commits ahead of `main` on this branch.

- [ ] **Step 4: Stop**

Do **not** invoke `superpowers:finishing-a-development-branch`. The implementation is done; control returns to the parent `execute-plan` skill for the functional review gate (Phase 7), simplify gate (Phase 8), external code review (Phase 9), and close-out (Phase 10).

---

## Self-review notes

**Spec coverage check:**
- §Architecture (six files in `mapping/` + one schema + one test dependency) → mapped to File Structure section.
- §Components: `VendorMappingSnapshot` → Task 7; `VendorResolution` → Task 5; `VendorCategory` → Task 2; `SourceType` → Task 3; `ProductName` → Task 4; `package-info` → Task 7.
- §JSON Schema (full document) → Task 6 ships the schema verbatim.
- §Data flow → no implementation needed; reflected in Task 7 interface signature.
- §Error handling → covered in Task 5 (null guards), Tasks 2/3/4 (`fromWireForm` returning empty).
- §Testing: all five test classes accounted for, every named test method appears in a task. The 6 positive + 13 negative schema tests are all in Task 6.
- §Test-scope dependency → Task 1.
- §Build gate (`./mvnw verify` green) → Task 8.
- §Field-name constant convention → enforced in Task 5.
- §Non-goals → reflected in the Hard non-goals callout in the header.
- §Acceptance signals → mapped 1:1 by Task 8 to the work plan's acceptance signals.

**Type-consistency check:**
- `VendorResolution.FIELD_VENDOR_SERVICE_ID` (and four siblings) — used in Task 5 test, defined in Task 5 source.
- `VendorCategory.OTHER` — referenced from `VendorResolution.UNKNOWN` initializer in Task 5; defined in Task 2.
- `VendorMappingSnapshot.lookup(ProductName, SourceType, String)` signature — referenced in Task 7; depends on `ProductName` (Task 4), `SourceType` (Task 3), `VendorResolution` (Task 5) — all defined before Task 7.
- `BundleSchemaTest.SCHEMA_CLASSPATH` (`/vendor-mapping/schema/v1.json`) — matches the file created in Task 6 Step 3 (`src/main/resources/vendor-mapping/schema/v1.json` is at classpath `/vendor-mapping/schema/v1.json`).
- All 19 fixture file names listed in Task 6 Step 4/5 match the `validateFixture(...)` calls in Task 6 Step 1's test code.

**Placeholder scan:** no TODO/TBD/FIXME, no "similar to Task N", no "implement appropriate X". Every code block is complete; every fixture has its full JSON content; every command has expected output described.
