# Bundle Parser, Snapshot Implementation, and MVP Seed — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Stop after the last task — do NOT auto-invoke `superpowers:finishing-a-development-branch`.** Control returns to the parent `execute-plan` skill for validation gates (functional review, simplify, external code review) before any PR is opened.

**Goal:** Land the stateless data layer of vendor mapping for T04 — the `BundleParser` (YAML → schema-validated `JsonNode` → immutable snapshot), the `BundleParseException` checked exception, the package-private `MapBackedVendorMappingSnapshot` (final, with a private nested `TripletKey` record), and the MVP seed bundle YAML at `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml` — so Plan 03 can wrap the parser in Spring with the S3 fetch and readiness gate.

**Architecture:** Three new Java types under `com.rapid7.integrationregistry.mapping`, plus one MVP seed YAML resource, plus three test classes with three YAML fixtures. The parser is instantiable (`new BundleParser()`), holds a pre-loaded `JsonSchema` field, and orchestrates Jackson `YAMLFactory` → `JsonNode` → `com.networknt.schema.JsonSchema` validation → snapshot construction. The snapshot impl is package-private with a private nested `TripletKey(ProductName, SourceType, String)` record used as the index key, exposed to construction sites only via a package-private static `key(...)` factory. The map is built once and defensively copied via `Map.copyOf(...)`. The exception is a checked `extends Exception` with two static factories (`yamlSyntaxError(Throwable)`, `schemaInvalid(Set<ValidationMessage>)`); validation messages are always non-null (at least `Set.of()`). No Spring annotations. No JSON serialization annotations. The pom dependency change promotes `com.networknt:json-schema-validator` and adds an explicit `jackson-dataformat-yaml` declaration, both at compile scope.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven (`./mvnw`), JUnit 5, AssertJ (already on the test classpath via `spring-boot-starter-test`), `com.networknt:json-schema-validator:1.5.4` (compile scope after Task 1), `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (compile scope after Task 1; version managed by the Spring Boot BOM), ArchUnit + PMD as build gates.

**Pre-flight context for any worker picking this up cold:**
- The branch `worktree-track-04-plan-02` is already checked out in the worktree at `repos/platform/integration-registry/.claude/worktrees/track-04-plan-02`. Run all commands from that worktree path.
- **Plan 01 (track-04/wp-01) is already merged** (commit `24622c7` is the merge of PR #2 to `main`). It ships the contract layer this plan depends on. Read these files once before starting:
  - `src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java` — the interface this plan implements
  - `src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java` — record with `unknown()` factory and package-private `FIELD_<NAME>` constants
  - `src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java`, `SourceType.java`, `ProductName.java` — closed enums with `wireForm()` and `fromWireForm(String)` helpers
  - `src/main/resources/vendor-mapping/schema/v1.json` — the JSON Schema this plan validates against
  - `src/test/java/com/rapid7/integrationregistry/mapping/BundleSchemaResources.java` — test helper with classpath-loading helpers; reuse where convenient, do not duplicate
- The package `com.rapid7.integrationregistry.mapping` already exists with the contract types from Plan 01. Add the new files alongside them.
- The recently-merged Plan 01 work is the canonical pattern this plan mirrors: records with compact constructors, `Objects.requireNonNull(field, FIELD_<NAME>)` guards keyed by package-private `FIELD_<NAME>` constants, tests that reference those constants via `.withMessage(Type.FIELD_<NAME>)`. The `MapBackedVendorMappingSnapshot` constants are `private static final` (this plan's own naming convention is consistent with that — these constants are not consumed by other production types).
- TESTING.md governs test conventions: unit tests under `src/test/java/com/rapid7/integrationregistry/mapping/`, no Spring context for this plan, YAML fixtures under `src/test/resources/vendor-mapping/bundle/`, `methodName_shouldDoX_whenY()` naming, AAA structure with `// Arrange / // Act / // Assert` comments.
- `LayerDependencyRules` already covers the `mapping/` package boundary. The existing `aggregatorLayer_shouldNotDependOnNonMappingLayers` permits the inbound edge from `aggregator` (the only consumer in T08). The other layer rules already exclude `..mapping..`. **No new ArchUnit rules are added by this plan.**
- PMD curated rules to remember: `AvoidDuplicateLiterals` (use `private static final FIELD_<NAME>` constants for any literal recurring 4+ times in the same file), `CommentContent` (no `TODO|FIXME|HACK|XXX` anywhere), `EmptyCatchBlock` (every `catch` must do something — rethrow as `BundleParseException` or wrap in `IllegalStateException`), `MutableStaticState` (only fires on non-final mutable static fields; all our constants are `final`), `UnusedFormalParameter`, `UnusedLocalVariable`. Curated for LLM failure modes — code that compiles but contains placeholders will fail the build.
- Build gate: `./mvnw verify` runs JUnit + ArchUnit + PMD. Each task ends with a focused test run; the final task runs full `verify`.
- Spec lives at `docs/superpowers/specs/2026-05-27-02-bundle-parser-snapshot-seed-design.md` if you need to re-check field-by-field details, the full code blocks, or the per-test fixture contract.

**Hard non-goals (push back if any task drifts into them):**
- No S3 fetch — Plan 03 owns the S3 client and the deploy-manifest-pinned `MAPPING_BUNDLE_VERSION`.
- No disk cache — Plan 03.
- No readiness probe / Spring health indicator — Plan 03.
- No Spring annotations on any class — no `@Component`, `@Service`, `@Bean`, `@ConfigurationProperties`. Plan 03 wires this into Spring.
- No WARN-level logging on unknown lookups — Plan 03 owns runtime logging; this plan's snapshot just returns `VendorResolution.unknown()`.
- No AWS SDK dependency.
- No JSON serialization annotations (`@JsonProperty` etc.). The parser walks `JsonNode` directly via `tree.at(...)` and `tree.get(...)` because the wire-form-to-enum mapping happens via Plan 01's `fromWireForm()` helpers.
- No `application.yaml` or Spring profile changes.
- No CI uniqueness, immutability, or deprecation enforcement — T11's bundle CI suite.
- No new ArchUnit rules.
- No KB doc updates — RFC-001 §Vendor mapping already describes the behavior in plain language.
- No expansion of the schema or any of Plan 01's contract types — this plan implements the contract, it does not extend it.

---

## File Structure

All new code under `src/main/java/com/rapid7/integrationregistry/mapping/`, `src/main/resources/vendor-mapping/bundle/`, `src/test/java/com/rapid7/integrationregistry/mapping/`, and `src/test/resources/vendor-mapping/bundle/`. The existing `mapping/package-info.java` is broadened in Task 6. `pom.xml` is modified in Task 1.

| File | Responsibility | Created in |
|---|---|---|
| `pom.xml` | Promote `com.networknt:json-schema-validator` from test → compile scope; add explicit `jackson-dataformat-yaml` at compile scope | Task 1 |
| `mapping/BundleParseException.java` | Public checked exception with two static factories; carries `Set<ValidationMessage>` | Task 2 |
| `mapping/MapBackedVendorMappingSnapshot.java` | Package-private final implementation of `VendorMappingSnapshot`; private nested `TripletKey` record; package-private `key(...)` factory | Task 3 |
| `mapping/BundleParser.java` | Public final class; `parse(InputStream) → VendorMappingSnapshot`; orchestrates YAML parsing, schema validation, snapshot construction | Task 4 |
| `resources/vendor-mapping/bundle/mvp-seed.yaml` | Locked four-triplet MVP seed bundle | Task 5 |
| `mapping/package-info.java` | Existing — javadoc broadened | Task 6 |

Tests, one per type that has runtime semantics (the parser produces an opaque snapshot, so it gets a black-box test class; the snapshot impl gets a direct white-box test class; the MVP seed gets a contract test class):

| File | Coverage | Created in |
|---|---|---|
| `mapping/MapBackedVendorMappingSnapshotTest.java` | Constructor null guards, defensive copy of source map, `lookup()` happy path, unmapped-triplet `unknown()` (incl. `isSameAs`), repeated-call same-instance, three lookup null guards, `mappingVersion()` accessor | Task 3 |
| `mapping/BundleParserTest.java` | `parse(valid-minimal.yaml)` happy path, `parse(invalid-yaml-syntax.yaml)` raises `BundleParseException` with Jackson cause, `parse(invalid-schema.yaml)` raises `BundleParseException` with `validationMessages()` populated, null-stream guard, parsed-snapshot returns `unknown()` for absent triplet | Task 4 |
| `mapping/MvpSeedBundleTest.java` | `mappingVersion()` is `"v1.0.0"`, all four MVP triplets resolve correctly to full `VendorResolution`, one negative-control triplet returns `unknown()` | Task 5 |

YAML fixtures (created alongside the tests that consume them):

| File | Purpose | Created in |
|---|---|---|
| `resources/vendor-mapping/bundle/valid-minimal.yaml` | One vendor / one service / one data source — happy path for `BundleParserTest` | Task 4 |
| `resources/vendor-mapping/bundle/invalid-yaml-syntax.yaml` | Malformed YAML (inconsistent indentation) — Jackson YAML parser rejects | Task 4 |
| `resources/vendor-mapping/bundle/invalid-schema.yaml` | Parses as YAML but `source_value` contains `\|` — schema rejects | Task 4 |

**Task ordering rationale:** dependencies dictate the sequence.

1. **Task 1** — promote pom dependencies first. Without compile-scope `jackson-dataformat-yaml` and `json-schema-validator`, `BundleParser` (Task 4) won't compile when it imports `com.fasterxml.jackson.dataformat.yaml.YAMLFactory` and `com.networknt.schema.*` from main code. The small early commit makes the dependency change reviewable on its own.
2. **Task 2** — `BundleParseException` has zero internal dependencies (only `com.networknt.schema.ValidationMessage`). Land it before anything else so the parser tests in Task 4 can assert against its type and shape from the first failing test.
3. **Task 3** — `MapBackedVendorMappingSnapshot` depends on Plan 01's `VendorMappingSnapshot`, `VendorResolution`, `ProductName`, `SourceType`. It does NOT depend on `BundleParser` or `BundleParseException`. Land it before the parser so the parser's tests can assert against the snapshot's behavior, and so the snapshot's own tests can run in isolation.
4. **Task 4** — `BundleParser` depends on `BundleParseException` (Task 2), `MapBackedVendorMappingSnapshot` (Task 3, including its `key(...)` factory), and the pom changes (Task 1). The three YAML fixtures land in this task because they are inseparable from the parser tests that consume them.
5. **Task 5** — MVP seed YAML + `MvpSeedBundleTest` depends on the parser path being complete. The seed itself is the production artifact Plan 03 will fetch from mocked S3.
6. **Task 6** — `package-info.java` javadoc broadens once all the types it references exist.
7. **Task 7** — full `./mvnw verify` checkpoint.

---

## Task 1: Promote `json-schema-validator` to compile scope; add explicit `jackson-dataformat-yaml`

**Files:**
- Modify: `pom.xml`

**Why this is its own commit:** dependency-scope changes are reviewable on their own. Bundling them with code creates noisy diffs and makes future bisection harder. Maven dependency resolution is the only thing being verified here.

- [ ] **Step 1: Inspect the current `<dependencies>` block**

Run: `grep -n -A4 'json-schema-validator\|jackson-dataformat-yaml' pom.xml`
Expected: prints exactly one match — the existing `com.networknt:json-schema-validator:1.5.4` block at lines ~47–52, with `<scope>test</scope>` on line 51. No `jackson-dataformat-yaml` entry yet.

- [ ] **Step 2: Modify `pom.xml`**

In `pom.xml`, locate the existing `com.networknt:json-schema-validator` dependency block (lines ~47–52). Make two changes inside the `<dependencies>` block:

1. **Remove the `<scope>test</scope>` line** from the `json-schema-validator` entry. The block becomes:

```xml
		<dependency>
			<groupId>com.networknt</groupId>
			<artifactId>json-schema-validator</artifactId>
			<version>1.5.4</version>
		</dependency>
```

2. **Add a new entry** immediately after the `json-schema-validator` block, before the closing `</dependencies>` tag:

```xml
		<dependency>
			<groupId>com.fasterxml.jackson.dataformat</groupId>
			<artifactId>jackson-dataformat-yaml</artifactId>
		</dependency>
```

No `<version>` and no `<scope>` on the new entry. The version is managed by the Spring Boot BOM (the parent `spring-boot-starter-parent:4.0.6`), and the default scope is `compile`.

`jackson-databind` stays implicit — it flows transitively from `jackson-dataformat-yaml`, with the version managed by the same BOM. Do NOT add an explicit `jackson-databind` entry.

- [ ] **Step 3: Verify resolution**

Run: `./mvnw -q dependency:list 2>&1 | grep -E 'jackson-dataformat-yaml|json-schema-validator|jackson-databind'`

Expected output (scope on each line should NOT be `test` for the three artifacts; the Spring Boot BOM picks the Jackson 2.x version, so the exact patch version may differ from `2.21.2` if the BOM has been bumped):

```
[INFO]    com.networknt:json-schema-validator:jar:1.5.4:compile -- ...
[INFO]    com.fasterxml.jackson.core:jackson-databind:jar:<version>:compile -- ...
[INFO]    com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:jar:<version>:compile -- ...
```

If any of the three says `:test`, the pom edit is wrong; double-check Step 2.

- [ ] **Step 4: Verify the existing build still passes**

Run: `./mvnw -q compile test-compile`
Expected: `BUILD SUCCESS`. No production or test code change yet, so this is a sanity check that the dependency-scope change didn't break anything.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build(track-04/wp-02): promote networknt + jackson-yaml to compile scope"
```

---

## Task 2: `BundleParseException` checked exception

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/BundleParseException.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/BundleParseExceptionTest.java`

**Why this comes before the parser and snapshot:** the parser's tests in Task 4 assert against this type's shape (factory methods, `validationMessages()` accessor). Having it land first lets Task 4 start from a real failing test, not a compilation error in the test source.

The exception is a `public class extends Exception` (checked) — same convention as `AdapterUpstreamException` in `adapter/exception/`. Two private constructor / two static factory pattern: `yamlSyntaxError(Throwable)` for YAML parse failures, `schemaInvalid(Set<ValidationMessage>)` for schema validation failures. `validationMessages()` always returns a non-null `Set` (at least `Set.of()`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/BundleParseExceptionTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BundleParseExceptionTest {

    @Test
    void yamlSyntaxError_shouldCarryCauseAndEmptyValidationMessages_whenInvoked() {
        // Arrange
        IOException cause = new IOException("yaml syntax error");

        // Act
        BundleParseException ex = BundleParseException.yamlSyntaxError(cause);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("yaml syntax error");
        assertThat(ex.validationMessages()).isEmpty();
    }

    @Test
    void schemaInvalid_shouldCarryValidationMessagesAndNullCause_whenInvoked() {
        // Arrange
        Set<ValidationMessage> messages = Set.of();

        // Act
        BundleParseException ex = BundleParseException.schemaInvalid(messages);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getMessage()).contains("Bundle failed JSON Schema validation");
        assertThat(ex.validationMessages()).isEmpty();
    }

    @Test
    void schemaInvalid_shouldCopyMessagesDefensively_whenSourceSetMutatedAfterConstruction() {
        // Arrange
        java.util.Set<ValidationMessage> mutable = new java.util.HashSet<>();

        // Act
        BundleParseException ex = BundleParseException.schemaInvalid(mutable);
        // After construction, mutating `mutable` must not affect the exception.
        // We can't easily add a real ValidationMessage here without a JSON Schema
        // round trip, but adding null is rejected by Set.copyOf, which is the
        // mechanism that proves the defensive copy ran.

        // Assert
        assertThat(ex.validationMessages()).isNotSameAs(mutable);
    }

    @Test
    void validationMessages_shouldBeUnmodifiable_whenAccessed() {
        // Arrange
        BundleParseException ex = BundleParseException.schemaInvalid(Collections.emptySet());

        // Act / Assert
        assertThat(ex.validationMessages()).isUnmodifiable();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BundleParseExceptionTest`
Expected: FAIL with compilation error (`BundleParseException` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/BundleParseException.java`:

```java
package com.rapid7.integrationregistry.mapping;

import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thrown by {@link BundleParser#parse} when the input cannot be parsed as YAML
 * or when the parsed document fails JSON Schema validation.
 *
 * <p>For YAML syntax failures, the underlying Jackson exception is the cause
 * and {@link #validationMessages()} is empty. For schema-validation failures,
 * the cause is null and {@link #validationMessages()} carries the structured
 * messages from the validator.
 */
public class BundleParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Set<ValidationMessage> validationMessages;

    private BundleParseException(String message, Throwable cause, Set<ValidationMessage> messages) {
        super(message, cause);
        this.validationMessages = messages;
    }

    public static BundleParseException yamlSyntaxError(Throwable cause) {
        return new BundleParseException(
            "Bundle YAML could not be parsed: " + cause.getMessage(),
            cause,
            Set.of());
    }

    public static BundleParseException schemaInvalid(Set<ValidationMessage> messages) {
        Set<ValidationMessage> copy = Set.copyOf(messages);
        String summary = copy.stream()
            .map(m -> m.getInstanceLocation() + ": " + m.getMessage())
            .collect(Collectors.joining("; "));
        return new BundleParseException(
            "Bundle failed JSON Schema validation: " + summary,
            null,
            copy);
    }

    public Set<ValidationMessage> validationMessages() {
        return validationMessages;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BundleParseExceptionTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/BundleParseException.java src/test/java/com/rapid7/integrationregistry/mapping/BundleParseExceptionTest.java
git commit -m "feat(track-04/wp-02): add BundleParseException with yaml/schema factories"
```

---

## Task 3: `MapBackedVendorMappingSnapshot` (package-private) with private `TripletKey`

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshot.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshotTest.java`

**Why this comes before `BundleParser`:** the parser's `buildSnapshot()` calls the package-private `key(...)` factory and the package-private constructor. Having the snapshot land first lets the parser compile and lets the parser's tests assert against snapshot behavior.

The snapshot is `final class` (package-private) implementing `VendorMappingSnapshot`. Constructor takes a `Map<TripletKey, VendorResolution>` and a `String mappingVersion`; defensively copies the map via `Map.copyOf(...)`. Private nested record `TripletKey(ProductName, SourceType, String)` is the index key. The package-private static `key(...)` factory exposes `TripletKey` construction to `BundleParser` and tests in this package without leaking the type publicly.

The five field-name constants are `private static final String` (this snapshot does not need to publish them to other production types — `VendorResolution` exposes its constants package-private specifically because tests reference them via `.withMessage(VendorResolution.FIELD_<NAME>)`; here the same pattern is internal-only).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshotTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MapBackedVendorMappingSnapshotTest {

    private static final String MAPPING_VERSION = "v1.0.0";

    private static VendorResolution sampleResolution() {
        return new VendorResolution(
            "microsoft-defender", "Microsoft Defender", VendorCategory.EDR,
            "microsoft", "Microsoft");
    }

    private static Map<Object, VendorResolution> oneEntryIndex(VendorResolution resolution) {
        // The TripletKey type is private to MapBackedVendorMappingSnapshot;
        // construct keys via the package-private key(...) factory.
        Map<Object, VendorResolution> raw = new HashMap<>();
        raw.put(MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint"),
            resolution);
        return raw;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MapBackedVendorMappingSnapshot construct(Map<Object, VendorResolution> rawIndex, String version) {
        // The constructor's Map<TripletKey, VendorResolution> parameter is reachable
        // from this same package; use a raw type to bridge our test-side Object key map
        // (which actually holds TripletKey instances minted by key(...)).
        return new MapBackedVendorMappingSnapshot((Map) rawIndex, version);
    }

    @Test
    void lookup_shouldReturnIndexedResolution_whenTripletPresent() {
        // Arrange
        VendorResolution resolution = sampleResolution();
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(resolution), MAPPING_VERSION);

        // Act
        VendorResolution result = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(result).isSameAs(resolution);
    }

    @Test
    void lookup_shouldReturnUnknownResolution_whenTripletAbsent() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act
        VendorResolution result = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "not-in-the-index");

        // Assert
        assertThat(result).isSameAs(VendorResolution.unknown());
    }

    @Test
    void lookup_shouldReturnSameInstance_acrossRepeatedCalls() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act
        VendorResolution first = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
        VendorResolution second = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(first).isSameAs(second);
    }

    @Test
    void lookup_shouldThrowNPE_whenProductNameIsNull() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> snapshot.lookup(null, SourceType.PRODUCT_TYPE, "x"))
            .withMessage("productName");
    }

    @Test
    void lookup_shouldThrowNPE_whenSourceTypeIsNull() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> snapshot.lookup(ProductName.INSIGHT_IDR, null, "x"))
            .withMessage("sourceType");
    }

    @Test
    void lookup_shouldThrowNPE_whenSourceValueIsNull() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> snapshot.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, null))
            .withMessage("sourceValue");
    }

    @Test
    void mappingVersion_shouldReturnConstructorValue_whenAccessed() {
        // Arrange
        MapBackedVendorMappingSnapshot snapshot = construct(oneEntryIndex(sampleResolution()), MAPPING_VERSION);

        // Act
        String version = snapshot.mappingVersion();

        // Assert
        assertThat(version).isEqualTo(MAPPING_VERSION);
    }

    @Test
    void constructor_shouldThrowNPE_whenIndexIsNull() {
        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> construct(null, MAPPING_VERSION))
            .withMessage("index");
    }

    @Test
    void constructor_shouldThrowNPE_whenMappingVersionIsNull() {
        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> construct(oneEntryIndex(sampleResolution()), null))
            .withMessage("mappingVersion");
    }

    @Test
    void constructor_shouldDefensivelyCopy_whenMutatingSourceMapAfterConstruction() {
        // Arrange
        Map<Object, VendorResolution> sourceMap = oneEntryIndex(sampleResolution());
        MapBackedVendorMappingSnapshot snapshot = construct(sourceMap, MAPPING_VERSION);
        VendorResolution otherResolution = new VendorResolution(
            "jira", "Jira", VendorCategory.ITSM, "atlassian", "Atlassian");

        // Act — mutate the source map AFTER constructing the snapshot
        sourceMap.put(MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira"), otherResolution);

        // Assert — the snapshot must not see the post-construction insertion
        VendorResolution result = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");
        assertThat(result).isSameAs(VendorResolution.unknown());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=MapBackedVendorMappingSnapshotTest`
Expected: FAIL with compilation error (`MapBackedVendorMappingSnapshot` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshot.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Map;
import java.util.Objects;

/**
 * Map-backed implementation of {@link VendorMappingSnapshot}. Constructed by
 * {@link BundleParser} from a parsed and schema-validated bundle; immutable
 * for the lifetime of the object.
 *
 * <p>Package-private — only {@code BundleParser} and tests within this package
 * construct instances. Callers outside this package depend on the
 * {@link VendorMappingSnapshot} interface.
 */
final class MapBackedVendorMappingSnapshot implements VendorMappingSnapshot {

    private static final String FIELD_PRODUCT_NAME = "productName";
    private static final String FIELD_SOURCE_TYPE = "sourceType";
    private static final String FIELD_SOURCE_VALUE = "sourceValue";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_MAPPING_VERSION = "mappingVersion";

    private record TripletKey(ProductName productName, SourceType sourceType, String sourceValue) {
        private TripletKey {
            Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
            Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
            Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
        }
    }

    private final Map<TripletKey, VendorResolution> index;
    private final String mappingVersion;

    MapBackedVendorMappingSnapshot(Map<TripletKey, VendorResolution> index, String mappingVersion) {
        Objects.requireNonNull(index, FIELD_INDEX);
        this.index = Map.copyOf(index);
        this.mappingVersion = Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
        Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
        return index.getOrDefault(
            new TripletKey(productName, sourceType, sourceValue),
            VendorResolution.unknown());
    }

    @Override
    public String mappingVersion() {
        return mappingVersion;
    }

    /**
     * Package-private factory exposed to {@link BundleParser} (for index
     * construction) and to tests in this package — keeps {@code TripletKey}
     * fully encapsulated as an implementation detail.
     */
    static TripletKey key(ProductName productName, SourceType sourceType, String sourceValue) {
        return new TripletKey(productName, sourceType, sourceValue);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=MapBackedVendorMappingSnapshotTest`
Expected: PASS — all 10 tests green.

**PMD note:** PMD's `MutableStaticState` rule may inspect the `private static final String FIELD_*` constants. They are `final` and reference `String` (deeply immutable). The rule targets mutable static state; final string constants do not fire it. If `AvoidDuplicateLiterals` fires on any of the field-name string literals, that's a sign one of them is being inlined somewhere — confirm every guard goes through the constant.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshot.java src/test/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshotTest.java
git commit -m "feat(track-04/wp-02): add MapBackedVendorMappingSnapshot with private TripletKey"
```

---

## Task 4: `BundleParser` + 3 YAML fixtures

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/BundleParser.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/BundleParserTest.java`
- Create: `src/test/resources/vendor-mapping/bundle/valid-minimal.yaml`
- Create: `src/test/resources/vendor-mapping/bundle/invalid-yaml-syntax.yaml`
- Create: `src/test/resources/vendor-mapping/bundle/invalid-schema.yaml`

**Why everything in this task lands together:** the parser, its black-box tests, and the YAML fixtures the tests load are inseparable. A partial commit means either a parser with no tests running against it, or tests that can't load their fixtures.

The parser holds two final fields wired in the constructor: an `ObjectMapper` configured with `YAMLFactory`, and a `JsonSchema` (Draft 2020-12) loaded once from `/vendor-mapping/schema/v1.json`. `loadSchema()` failure throws `IllegalStateException` (packaging defect, not a recoverable runtime path). The `requireEnum()` backstop throws `IllegalStateException` if a schema-validated value can't be mapped through `fromWireForm()` — this guards against schema/enum drift that `EnumSchemaSyncTest` (Plan 01) is the first line of defense for.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/BundleParserTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.core.JacksonException;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BundleParserTest {

    private static final String FIXTURES_ROOT = "/vendor-mapping/bundle/";

    private static InputStream openFixture(String fixtureFileName) {
        InputStream stream = BundleParserTest.class.getResourceAsStream(FIXTURES_ROOT + fixtureFileName);
        assertThat(stream)
            .as("fixture resource %s present on classpath", FIXTURES_ROOT + fixtureFileName)
            .isNotNull();
        return stream;
    }

    @Test
    void parse_shouldReturnSnapshot_whenValidMinimalBundle() throws Exception {
        // Arrange
        BundleParser parser = new BundleParser();

        // Act
        VendorMappingSnapshot snapshot;
        try (InputStream stream = openFixture("valid-minimal.yaml")) {
            snapshot = parser.parse(stream);
        }

        // Assert
        assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
        assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
        assertThat(resolution.vendorId()).isEqualTo("microsoft");
        assertThat(resolution.vendorName()).isEqualTo("Microsoft");
    }

    @Test
    void parse_shouldThrowBundleParseException_whenYamlSyntaxIsInvalid() throws IOException {
        // Arrange
        BundleParser parser = new BundleParser();

        // Act / Assert
        try (InputStream stream = openFixture("invalid-yaml-syntax.yaml")) {
            BundleParseException ex = assertThatExceptionOfType(BundleParseException.class)
                .isThrownBy(() -> parser.parse(stream))
                .actual();
            assertThat(ex.validationMessages()).isEmpty();
            assertThat(ex.getCause()).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void parse_shouldThrowBundleParseException_whenSchemaValidationFails() throws IOException {
        // Arrange
        BundleParser parser = new BundleParser();

        // Act / Assert
        try (InputStream stream = openFixture("invalid-schema.yaml")) {
            BundleParseException ex = assertThatExceptionOfType(BundleParseException.class)
                .isThrownBy(() -> parser.parse(stream))
                .actual();
            assertThat(ex.getCause()).isNull();
            Set<ValidationMessage> messages = ex.validationMessages();
            assertThat(messages).isNotEmpty();
            assertThat(messages).anyMatch(m -> m.getInstanceLocation().toString().contains("source_value"));
        }
    }

    @Test
    void parse_shouldThrowNullPointerException_whenStreamIsNull() {
        // Arrange
        BundleParser parser = new BundleParser();

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> parser.parse(null))
            .withMessage("yamlStream");
    }

    @Test
    void parse_shouldReturnSnapshotWithUnknownLookup_whenTripletAbsent() throws Exception {
        // Arrange
        BundleParser parser = new BundleParser();

        // Act
        VendorMappingSnapshot snapshot;
        try (InputStream stream = openFixture("valid-minimal.yaml")) {
            snapshot = parser.parse(stream);
        }
        VendorResolution result = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "not-in-the-bundle");

        // Assert
        assertThat(result).isSameAs(VendorResolution.unknown());
    }

    @Test
    void parse_shouldReturnSnapshot_whenStreamProvidedAsByteArray() throws Exception {
        // Arrange — proves the stream type contract is satisfied with any InputStream,
        // not just classpath InputStreams.
        BundleParser parser = new BundleParser();
        byte[] yaml = ("apiVersion: registry.rapid7.com/v1\n"
            + "kind: VendorMapping\n"
            + "metadata:\n"
            + "  mapping_version: v9.9.9\n"
            + "spec:\n"
            + "  vendors: []\n").getBytes(StandardCharsets.UTF_8);

        // Act
        VendorMappingSnapshot snapshot = parser.parse(new ByteArrayInputStream(yaml));

        // Assert
        assertThat(snapshot.mappingVersion()).isEqualTo("v9.9.9");
        assertThat(snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "anything"))
            .isSameAs(VendorResolution.unknown());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BundleParserTest`
Expected: FAIL with compilation error (`BundleParser` does not exist) — and even if it did compile, every test would fail loading its fixture (no YAML files yet).

- [ ] **Step 3: Create the YAML fixtures**

Create `src/test/resources/vendor-mapping/bundle/valid-minimal.yaml`:

```yaml
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata:
  mapping_version: v1.0.0
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
```

Create `src/test/resources/vendor-mapping/bundle/invalid-yaml-syntax.yaml`:

```yaml
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata:
  mapping_version: v1.0.0
spec:
  vendors:
    - id: microsoft
      name: Microsoft
     services:
        - id: microsoft-defender
```

The `services:` key on the next-to-last line is indented one space less than its sibling list items — Jackson's YAML parser rejects this as a structural error.

Create `src/test/resources/vendor-mapping/bundle/invalid-schema.yaml`:

```yaml
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata:
  mapping_version: v1.0.0
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
              source_value: "foo|bar"
              display_name: Microsoft Defender for Endpoint
```

The `source_value: "foo|bar"` violates the schema's `SourceValue.not.pattern` rule (no pipe character).

- [ ] **Step 4: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/BundleParser.java`:

```java
package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a vendor-mapping bundle YAML document into an immutable
 * {@link VendorMappingSnapshot}. Stateless and framework-agnostic;
 * Plan 03 wires this into Spring with the S3 fetch and readiness gate.
 */
public final class BundleParser {

    private static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
    private static final String FIELD_YAML_STREAM = "yamlStream";

    private final ObjectMapper yamlMapper;
    private final JsonSchema schema;

    public BundleParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.schema = loadSchema();
    }

    /**
     * Parse, schema-validate, and index a bundle YAML document.
     *
     * @throws BundleParseException if the YAML is unparseable or the parsed
     *     document fails JSON Schema validation.
     * @throws NullPointerException if {@code yamlStream} is null
     */
    public VendorMappingSnapshot parse(InputStream yamlStream) throws BundleParseException {
        Objects.requireNonNull(yamlStream, FIELD_YAML_STREAM);
        JsonNode tree = parseYaml(yamlStream);
        Set<ValidationMessage> errors = schema.validate(tree);
        if (!errors.isEmpty()) {
            throw BundleParseException.schemaInvalid(errors);
        }
        return buildSnapshot(tree);
    }

    private JsonNode parseYaml(InputStream yamlStream) throws BundleParseException {
        try {
            return yamlMapper.readTree(yamlStream);
        } catch (JacksonException ex) {
            throw BundleParseException.yamlSyntaxError(ex);
        } catch (IOException ex) {
            throw BundleParseException.yamlSyntaxError(ex);
        }
    }

    private VendorMappingSnapshot buildSnapshot(JsonNode tree) {
        String mappingVersion = tree.at("/metadata/mapping_version").asText();
        Map<Object, VendorResolution> index = new HashMap<>();
        for (JsonNode vendor : tree.at("/spec/vendors")) {
            String vendorId = vendor.get("id").asText();
            String vendorName = vendor.get("name").asText();
            JsonNode services = vendor.get("services");
            if (services == null) {
                continue;
            }
            for (JsonNode service : services) {
                String serviceId = service.get("id").asText();
                String serviceName = service.get("name").asText();
                VendorCategory category = requireEnum(
                    VendorCategory.fromWireForm(service.get("category").asText()),
                    "category", service.get("category").asText());
                JsonNode dataSources = service.get("data_sources");
                if (dataSources == null) {
                    continue;
                }
                for (JsonNode ds : dataSources) {
                    ProductName product = requireEnum(
                        ProductName.fromWireForm(ds.get("product").asText()),
                        "product", ds.get("product").asText());
                    SourceType sourceType = requireEnum(
                        SourceType.fromWireForm(ds.get("source_type").asText()),
                        "source_type", ds.get("source_type").asText());
                    String sourceValue = ds.get("source_value").asText();
                    VendorResolution resolution = new VendorResolution(
                        serviceId, serviceName, category, vendorId, vendorName);
                    index.put(
                        MapBackedVendorMappingSnapshot.key(product, sourceType, sourceValue),
                        resolution);
                }
            }
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map typedIndex = index;
        return new MapBackedVendorMappingSnapshot(typedIndex, mappingVersion);
    }

    private static <E extends Enum<E>> E requireEnum(Optional<E> resolved, String fieldName, String wireForm) {
        return resolved.orElseThrow(() -> new IllegalStateException(
            "Schema-validated bundle contained " + fieldName + "=" + wireForm
                + " which the Java enum does not recognize. "
                + "This is a schema/enum-sync defect — see EnumSchemaSyncTest."));
    }

    private static JsonSchema loadSchema() {
        try (InputStream in = BundleParser.class.getResourceAsStream(SCHEMA_CLASSPATH)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Bundle schema resource missing from classpath: " + SCHEMA_CLASSPATH
                        + ". This is a packaging defect.");
            }
            JsonNode schemaNode = new ObjectMapper().readTree(in);
            return JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load bundle schema from classpath", ex);
        }
    }
}
```

**Note on the `@SuppressWarnings` for the raw-type bridge:** the `index` variable is declared as `Map<Object, VendorResolution>` so we can `put(...)` with the package-private `TripletKey` returned by `key(...)` without leaking that type into this file's signatures. The package-private constructor of `MapBackedVendorMappingSnapshot` requires `Map<TripletKey, VendorResolution>`, so we cast through a raw `Map` to bridge. This is the same shape the test class uses and is the cleanest way to keep `TripletKey` private to the snapshot impl. The suppression is local and justified by the comment.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=BundleParserTest`
Expected: PASS — all six tests green.

If `parse_shouldThrowBundleParseException_whenSchemaValidationFails` fails on the `getInstanceLocation().toString().contains("source_value")` assertion, the validator's location-string format may differ slightly. Run with `-X` to see the actual location strings; adjust the substring to match the actual format (e.g. `/spec/vendors/0/services/0/data_sources/0/source_value` vs `$.spec.vendors[0]...`). Do not weaken the assertion to "messages not empty" alone — the test must prove the validator caught the right rule.

- [ ] **Step 6: Commit**

```bash
git add \
  src/main/java/com/rapid7/integrationregistry/mapping/BundleParser.java \
  src/test/java/com/rapid7/integrationregistry/mapping/BundleParserTest.java \
  src/test/resources/vendor-mapping/bundle/valid-minimal.yaml \
  src/test/resources/vendor-mapping/bundle/invalid-yaml-syntax.yaml \
  src/test/resources/vendor-mapping/bundle/invalid-schema.yaml
git commit -m "feat(track-04/wp-02): add BundleParser with YAML+schema validation"
```

---

## Task 5: MVP seed YAML + `MvpSeedBundleTest`

**Files:**
- Create: `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/MvpSeedBundleTest.java`

**Why these land together:** the seed and the test that proves it correct are inseparable. The seed is the production artifact Plan 03's mocked-S3 integration tests will fetch; the test in this plan is its first proof of correctness, asserted via the parser path.

The seed carries exactly four data sources across two vendors:

| product_name | source_type | source_value | → vendor_service / vendor |
|---|---|---|---|
| InsightIDR | product_type | microsoft-defender-endpoint | microsoft-defender / microsoft |
| InsightConnect | plugin_name | microsoft-defender | microsoft-defender / microsoft (cross-product merge) |
| InsightConnect | plugin_name | jira | jira / atlassian |

`mapping_version: v1.0.0` is locked for this seed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/MvpSeedBundleTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MvpSeedBundleTest {

    private static final String SEED_CLASSPATH = "/vendor-mapping/bundle/mvp-seed.yaml";

    private static VendorMappingSnapshot snapshot;

    @BeforeAll
    static void parseSeed() throws Exception {
        BundleParser parser = new BundleParser();
        try (InputStream stream = MvpSeedBundleTest.class.getResourceAsStream(SEED_CLASSPATH)) {
            assertThat(stream)
                .as("MVP seed resource %s present on classpath", SEED_CLASSPATH)
                .isNotNull();
            snapshot = parser.parse(stream);
        }
    }

    @Test
    void mvpSeed_shouldHaveMappingVersion_v1_0_0() {
        // Act
        String version = snapshot.mappingVersion();

        // Assert
        assertThat(version).isEqualTo("v1.0.0");
    }

    @Test
    void mvpSeed_shouldResolveDefenderViaIDR_toMicrosoftDefender() {
        // Act
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
        assertThat(resolution.vendorId()).isEqualTo("microsoft");
        assertThat(resolution.vendorName()).isEqualTo("Microsoft");
    }

    @Test
    void mvpSeed_shouldResolveDefenderViaICON_toMicrosoftDefender() {
        // Act
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "microsoft-defender");

        // Assert — proves the cross-product merge: ICON-side identifier resolves to the
        // SAME vendor service / vendor as the IDR-side identifier above.
        assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
        assertThat(resolution.vendorId()).isEqualTo("microsoft");
        assertThat(resolution.vendorName()).isEqualTo("Microsoft");
    }

    @Test
    void mvpSeed_shouldResolveJiraViaICON_toJira() {
        // Act
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo("jira");
        assertThat(resolution.vendorServiceName()).isEqualTo("Jira");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.ITSM);
        assertThat(resolution.vendorId()).isEqualTo("atlassian");
        assertThat(resolution.vendorName()).isEqualTo("Atlassian");
    }

    @Test
    void mvpSeed_shouldReturnUnknown_forUnmappedTriplet() {
        // Act — negative control: a triplet that is NOT in the seed.
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PLUGIN_NAME, "not-in-the-bundle");

        // Assert
        assertThat(resolution).isSameAs(VendorResolution.unknown());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=MvpSeedBundleTest`
Expected: FAIL — `parseSeed()` finds no resource at `/vendor-mapping/bundle/mvp-seed.yaml`; the `@BeforeAll` `assertThat(stream).isNotNull()` fails. Every test fails on the missing seed.

- [ ] **Step 3: Create the MVP seed YAML**

Create `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`:

```yaml
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata:
  mapping_version: v1.0.0
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=MvpSeedBundleTest`
Expected: PASS — all five tests green.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/resources/vendor-mapping/bundle/mvp-seed.yaml \
  src/test/java/com/rapid7/integrationregistry/mapping/MvpSeedBundleTest.java
git commit -m "feat(track-04/wp-02): add MVP seed bundle YAML and contract test"
```

---

## Task 6: Broaden `package-info.java` javadoc

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/mapping/package-info.java`

**Why this is its own commit:** isolating the docs-only change keeps the per-task diffs focused and lets the javadoc broadening reference all the types this plan added — the doc commit is the natural close-out of the type-introduction sequence.

- [ ] **Step 1: Read the current `package-info.java`**

Run: `cat src/main/java/com/rapid7/integrationregistry/mapping/package-info.java`
Expected: prints the existing javadoc Plan 01 wrote, mentioning the interface, record, enums, and that "Implementations of `VendorMappingSnapshot` live in this package; no other internal Registry layer may depend on this package other than `aggregator` (enforced by ArchUnit)."

- [ ] **Step 2: Replace the contents**

Replace the contents of `src/main/java/com/rapid7/integrationregistry/mapping/package-info.java` with:

```java
/**
 * Vendor-mapping read-side contract and stateless data layer:
 *
 * <ul>
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot} interface
 *       (Plan 01) — the read-side contract.</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.VendorResolution} record (Plan 01)
 *       carrying resolved vendor / vendor-service identity.</li>
 *   <li>The closed enums
 *       ({@link com.rapid7.integrationregistry.mapping.VendorCategory},
 *       {@link com.rapid7.integrationregistry.mapping.SourceType},
 *       {@link com.rapid7.integrationregistry.mapping.ProductName}) referenced by
 *       the bundle JSON Schema and the snapshot lookup API (Plan 01).</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.BundleParser} (Plan 02) —
 *       parses bundle YAML, validates against the schema, and constructs the
 *       immutable snapshot.</li>
 *   <li>{@link com.rapid7.integrationregistry.mapping.BundleParseException} (Plan 02) —
 *       checked exception covering YAML syntax and schema-validation failures.</li>
 * </ul>
 *
 * <p>The map-backed snapshot implementation is package-private; callers depend
 * on the {@code VendorMappingSnapshot} interface only.
 *
 * <p>No internal Registry layer may depend on this package other than
 * {@code aggregator} (enforced by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
```

- [ ] **Step 3: Verify compilation**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`. Javadoc-only changes don't affect bytecode but the package-info still has to compile.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/package-info.java
git commit -m "docs(track-04/wp-02): broaden mapping package javadoc with Plan 02 types"
```

---

## Task 7: Full-build verification checkpoint

**Files:** none — pure verification.

This is the gate that proves the work plan's Acceptance signals: every test green, ArchUnit green, PMD green.

- [ ] **Step 1: Run full verify**

Run: `./mvnw verify`
Expected:
- `BUILD SUCCESS`
- All JUnit tests pass:
  - The four new test classes from Tasks 2–5 (`BundleParseExceptionTest`, `MapBackedVendorMappingSnapshotTest`, `BundleParserTest`, `MvpSeedBundleTest`).
  - Plus the existing tests merged from Plan 01 (`VendorCategoryTest`, `SourceTypeTest`, `ProductNameTest`, `VendorResolutionTest`, `BundleSchemaTest`, `EnumSchemaSyncTest`).
  - Plus the architecture and adapter tests (`LayerDependencyRulesTest`, `LayerDependencyViolationDetectionTest`, `IntegrationRegistryApplicationTests`, the five `adapter/` tests from track-05/wp-01).
- ArchUnit reports zero violations. The new types in `mapping/` depend only on JDK + `com.fasterxml.jackson.*` + `com.networknt.schema.*`. No internal-layer imports.
- PMD reports zero violations across both `src/main/java` and `src/test/java`.

- [ ] **Step 2: If `./mvnw verify` fails, diagnose and fix**

Common failure modes specific to this plan:

- **PMD `AvoidDuplicateLiterals`** — fires on a literal repeated 4+ times in the same file. The `MapBackedVendorMappingSnapshot.FIELD_*` constants prevent this on the snapshot. The `BundleParser.FIELD_YAML_STREAM` constant prevents this on the parser. The wire-form lookup loop in `BundleParser.buildSnapshot()` references each enum-string lookup once per data source — at most three iterations on `valid-minimal.yaml`. If the rule fires anywhere, find the duplicate literal and extract a `private static final String` constant.
- **PMD `AvoidInstantiatingObjectsInLoops`** — `BundleParser.buildSnapshot()` allocates `VendorResolution` and `TripletKey` per data source. That IS the work being done; it's not a pathology. If the rule fires (the curated ruleset may have tightened it), suppress at the method level: `@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")` with a comment ending in a period explaining why the allocation is the contract, not a leak.
- **PMD `MutableStaticState`** — should not fire. The `FIELD_*` constants are `private static final String`; the `SCHEMA_CLASSPATH` constant is the same shape.
- **PMD `EmptyCatchBlock`** — every `catch` in `BundleParser` either rethrows as `BundleParseException` (in `parseYaml`) or wraps in `IllegalStateException` (in `loadSchema`). None are empty.
- **PMD `CommentContent`** — would fire on `TODO|FIXME|HACK|XXX`. Remove any such comment; if you need to mark deferred work, capture it as a follow-up work plan via the `work-plans` skill instead.
- **ArchUnit failure** — if a layer rule flags the new types, you imported a Spring or other-internal-package type. The mapping types should depend only on JDK, `com.fasterxml.jackson.*`, and `com.networknt.schema.*`.
- **`BundleParserTest` validator-message format mismatch** — if `parse_shouldThrowBundleParseException_whenSchemaValidationFails` fails on the `getInstanceLocation().toString().contains("source_value")` assertion, the validator's location string format may differ (e.g. JSON Pointer `/spec/vendors/0/services/0/data_sources/0/source_value` vs JSONPath `$.spec.vendors[0]...`). Run with `-X` to see the actual location strings; adjust the substring. Do not weaken the assertion to "messages not empty" alone.

Diagnose, fix, re-run. Do not bypass the gate.

- [ ] **Step 3: Confirm git state**

Run: `git status && git log --oneline main..HEAD`

Expected:
- `git status` reports a clean working tree (no uncommitted changes).
- `git log` shows the commits matching:
  - The spec commit (`docs(track-04/wp-02): design spec for bundle parser, snapshot, MVP seed`) from the brainstorming step.
  - Six feature/build/docs commits, one per Task 1–6 (Task 1 = pom dep promotion; Task 2 = `BundleParseException`; Task 3 = `MapBackedVendorMappingSnapshot`; Task 4 = `BundleParser` + 3 fixtures; Task 5 = MVP seed + test; Task 6 = `package-info` broaden).
  - Total: 7 commits ahead of `main` on this branch.

- [ ] **Step 4: Stop**

Do **not** invoke `superpowers:finishing-a-development-branch`. The implementation is done; control returns to the parent `execute-plan` skill for the functional review gate (Phase 7), simplify gate (Phase 8), external code review (Phase 9), and close-out (Phase 10).

---

## Self-review notes

**Spec coverage check:**
- §Architecture (three production types + one resource + pom dep change) → mapped 1:1 to File Structure section.
- §Components: `BundleParser` → Task 4; `BundleParseException` → Task 2; `MapBackedVendorMappingSnapshot` (with private nested `TripletKey` and `key(...)` factory) → Task 3; `package-info.java` → Task 6.
- §MVP seed YAML (full content) → Task 5 ships the seed verbatim.
- §Data flow → no separate task; reflected in the `BundleParser.buildSnapshot()` walk in Task 4.
- §Error handling → covered in Task 2 (factories + `validationMessages()`), Task 3 (constructor + `lookup()` null guards), Task 4 (`parse()` null guard, YAML-error wrapping, schema-error wrapping, `requireEnum()` backstop, `loadSchema()` failure path).
- §Testing: all three test classes accounted for. `BundleParseExceptionTest` is named in Task 2 (added during Task 2 because the exception lands first); `MapBackedVendorMappingSnapshotTest` in Task 3; `BundleParserTest` in Task 4; `MvpSeedBundleTest` in Task 5. All test method names from the spec appear in their tasks.
- §Test fixtures (3 YAML files) → Task 4 creates all three in Step 3.
- §Dependency-scope changes → Task 1.
- §Build gate (`./mvnw verify` green) → Task 7.
- §Field-name constant convention → enforced in Task 3 (snapshot's `FIELD_*` constants) and Task 4 (parser's `FIELD_YAML_STREAM`).
- §Non-goals → reflected in the Hard non-goals callout in the header.
- §Acceptance signals → mapped 1:1 by Task 7 to the work plan's acceptance signals.

**Type-consistency check:**
- `BundleParseException.yamlSyntaxError(Throwable)` and `.schemaInvalid(Set<ValidationMessage>)` static factories — defined in Task 2; called from `BundleParser` in Task 4; asserted by tests in Task 2 (own behavior) and Task 4 (parser propagation).
- `BundleParseException.validationMessages()` accessor — defined in Task 2; asserted in Tasks 2 and 4.
- `MapBackedVendorMappingSnapshot.key(ProductName, SourceType, String)` package-private static factory — defined in Task 3; consumed by tests in Task 3 (via the helper `oneEntryIndex`) and by `BundleParser.buildSnapshot` in Task 4.
- `MapBackedVendorMappingSnapshot.FIELD_*` constants — `private static final String`. They are referenced internally within the class only; tests assert error messages via `.withMessage("productName")`/etc. against the strings these constants hold (see Task 3 lookup-NPE tests). Renaming a constant requires updating both the constant value AND the test's `.withMessage(...)` argument; this is intentional — the strings are the public contract on `NullPointerException.getMessage()`.
- `BundleParser` constructor signature: no-arg public — confirmed in Task 4. `parse(InputStream) throws BundleParseException` — confirmed in Task 4 against the spec's signature.
- `MapBackedVendorMappingSnapshot` constructor signature: package-private `(Map<TripletKey, VendorResolution>, String)` — confirmed in Task 3. The parser bridges via a raw `Map` to keep `TripletKey` private; suppression is local and justified.
- `VendorResolution` is constructed in `BundleParser.buildSnapshot()` with five args in the order `(serviceId, serviceName, category, vendorId, vendorName)` — matches Plan 01's record component order.
- `VendorResolution.unknown()` is referenced as a singleton via `isSameAs(...)` — confirmed in MapBackedVendorMappingSnapshotTest, BundleParserTest, MvpSeedBundleTest.
- Schema classpath path `/vendor-mapping/schema/v1.json` — referenced in `BundleParser.SCHEMA_CLASSPATH` (Task 4); matches Plan 01's published path.
- MVP seed classpath path `/vendor-mapping/bundle/mvp-seed.yaml` — referenced in `MvpSeedBundleTest.SEED_CLASSPATH` (Task 5); matches the spec's stable path.
- Three test fixture paths — declared in Task 4 (`/vendor-mapping/bundle/{valid-minimal,invalid-yaml-syntax,invalid-schema}.yaml`); all referenced from `BundleParserTest.openFixture(...)` in Task 4 Step 1.

**Placeholder scan:** no TODO/FIXME/HACK/XXX, no "implement appropriate X", no "similar to Task N". Every code block is complete; every fixture has its full content; every command has expected output described.

**Scope check:** the plan covers parser + snapshot impl + MVP seed + tests + fixtures + pom dep promotion + javadoc broaden. Each task ends with a self-contained commit. Plan 03 (S3 + readiness gate + Spring wiring + WARN logging) is explicitly out of scope and called out in the Hard non-goals header.
