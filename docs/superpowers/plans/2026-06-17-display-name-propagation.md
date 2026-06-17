# Curated `display_name` Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the expanded data-source rows render the curated bundle `display_name` (e.g. "Microsoft Defender") instead of the raw source slug (e.g. `rapid7_insightconnect_microsoft_defender`).

**Architecture:** Introduce a new `DataSourceResolution(VendorResolution identity, String displayName)` record in the `mapping` package and change `VendorMappingSnapshot.lookup(...)` to return it. This keeps the curated `display_name` at data-source cardinality and leaves the 5-field `VendorResolution` (vendor-service identity) untouched — so the `equals()`-based bundle-integrity check in `VendorAggregator` keeps working. `BundleParser` reads the (schema-required) `display_name` into the new record; `VendorAggregator.resolveOne` stops substituting the raw `sourceValue`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Jackson 3 (`tools.jackson.*`), JUnit 5 + AssertJ + Mockito, Logback ListAppender, Testcontainers (Valkey), Maven wrapper.

## Global Constraints

- **Java 25** / **Spring Boot 4.0.6**. Jackson 3 annotations are `tools.jackson.databind.*` (NOT `com.fasterxml.*`); core annotations like `@JsonIgnoreProperties` stay `com.fasterxml.jackson.annotation.*`.
- **Spotless / Google Java Format is authoritative.** Never hand-format to match GJF. Run `./mvnw spotless:apply` before `./mvnw verify`.
- **PMD ruleset** (`pmd-ruleset.xml`) fails on empty catch blocks, unused code, structural bloat, placeholder leftovers. Suppress only locally with `@SuppressWarnings("PMD.<Rule>")` and a justification comment.
- **ArchUnit** enforces RFC-001 layer boundaries on main source only. `DataSourceResolution` lives in `com.rapid7.integrationregistry.mapping` (same package as `VendorResolution`); it must not import any `aggregator`, `service`, `controller`, `coordinator`, or `adapter` type.
- **Test conventions** (TESTING.md, binding): method naming `methodName_shouldDoX_whenY()`; Arrange-Act-Assert with explicit `// Arrange` / `// Act` / `// Assert` comments; one logical behavior per unit/controller test; unit-first pyramid; no `Date.now()`/random — pin constants.
- **`./mvnw verify` requires a running Docker daemon** (Valkey Testcontainers cache tests, ADR-006). Tests run under Surefire — no `*IT.java` suffix, no failsafe split.
- **Branch:** all work lands on `feat/display-name-propagation` (already created). No push/PR unless the developer explicitly asks.

---

## File Structure

**Main (create):**
- `src/main/java/com/rapid7/integrationregistry/mapping/DataSourceResolution.java` — new value record pairing vendor-service `identity` with the data-source `displayName`; carries the `unknown()` singleton (the single home for the fixed "Unknown" label).

**Main (modify):**
- `mapping/VendorMappingSnapshot.java` — `lookup(...)` return type `VendorResolution` → `DataSourceResolution`.
- `mapping/MapBackedVendorMappingSnapshot.java` — index value type + `lookup` return + default.
- `mapping/loader/VendorMappingSnapshotHolder.java` — `lookup` return type.
- `mapping/loader/LoggingVendorMappingSnapshot.java` — `lookup` return type; WARN reference check via `.identity()`.
- `mapping/BundleParser.java` — read `display_name`; build `DataSourceResolution`; index value type.
- `aggregator/VendorAggregator.java` — `resolveOne` consumes `DataSourceResolution`; uses `.identity()` + `.displayName()`; removes the `sourceValue` substitution; unmapped path uses the "Unknown" label.
- `aggregator/ResolvedInstance.java` — javadoc correction (no code change to fields).
- `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml` — header-comment correction.

**Test (create):**
- `src/test/java/com/rapid7/integrationregistry/mapping/DataSourceResolutionTest.java` — unit test for the new record.
- `src/test/resources/vendor-mapping/invalid-missing-display-name.json` — schema negative fixture.

**Test (modify):**
- `mapping/MapBackedVendorMappingSnapshotTest.java`, `mapping/MvpSeedBundleTest.java`, `mapping/BundleParserTest.java`, `mapping/BundleSchemaTest.java`
- `mapping/MapBackedSnapshotBuilder.java`, `testsupport/StubVendorMappingSnapshot.java`
- `mapping/loader/LoggingVendorMappingSnapshotTest.java`, `mapping/loader/VendorMappingSnapshotHolderTest.java`, `mapping/loader/S3VendorMappingBundleLoaderTest.java`, `mapping/loader/VendorMappingBootIntegrationTest.java`
- `aggregator/VendorAggregatorTest.java`
- `integration/ReadPathIntegrationTest.java`

**Out of scope (verified — no change):** `VendorResolution.java` and `VendorResolutionTest.java` (record untouched); `cache/FetchResultCodecTest.java` (cache serializes `NormalizedIntegration.integrationLabel`, not the aggregation-time `displayName`); all wire DTOs (`DataSourceDto`, `VendorServiceDetailResponse` — already read `displayName`, just receive a real value).

---

### Task 1: `DataSourceResolution` value type

Brand-new type with no inbound references yet, so it compiles and commits in isolation. Pure TDD.

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/DataSourceResolution.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/DataSourceResolutionTest.java`

**Interfaces:**
- Consumes: `VendorResolution` (existing, same package) — its `unknown()` singleton.
- Produces (later tasks rely on these exact signatures):
  - `public record DataSourceResolution(VendorResolution identity, String displayName)`
  - `public static DataSourceResolution unknown()` — returns a singleton whose `identity()` is `VendorResolution.unknown()` and whose `displayName()` is `"Unknown"`.
  - Compact constructor throws `NullPointerException` with message `"identity"` / `"displayName"` on null args.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/DataSourceResolutionTest.java`:

```java
package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DataSourceResolutionTest {

  private static final VendorResolution IDENTITY =
      new VendorResolution(
          "microsoft-defender", "Microsoft Defender", VendorCategory.EDR, "microsoft", "Microsoft");

  @Test
  void constructor_shouldBuildRecord_whenBothFieldsProvided() {
    // Act
    DataSourceResolution resolution =
        new DataSourceResolution(IDENTITY, "Microsoft Defender for Endpoint");

    // Assert
    assertThat(resolution.identity()).isSameAs(IDENTITY);
    assertThat(resolution.displayName()).isEqualTo("Microsoft Defender for Endpoint");
  }

  @Test
  void constructor_shouldThrowNPE_whenIdentityNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new DataSourceResolution(null, "label"))
        .withMessage("identity");
  }

  @Test
  void constructor_shouldThrowNPE_whenDisplayNameNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new DataSourceResolution(IDENTITY, null))
        .withMessage("displayName");
  }

  @Test
  void unknown_shouldCarryUnknownIdentityAndLabel_whenInvoked() {
    // Act
    DataSourceResolution unknown = DataSourceResolution.unknown();

    // Assert — identity is the VendorResolution singleton; label is the fixed "Unknown"
    assertThat(unknown.identity()).isSameAs(VendorResolution.unknown());
    assertThat(unknown.displayName()).isEqualTo("Unknown");
  }

  @Test
  void unknown_shouldReturnSameInstance_whenInvokedTwice() {
    // Act / Assert
    assertThat(DataSourceResolution.unknown()).isSameAs(DataSourceResolution.unknown());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=DataSourceResolutionTest`
Expected: FAIL — compilation error, `DataSourceResolution` does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/DataSourceResolution.java`:

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Objects;

/**
 * Result of resolving a raw product source identifier to its canonical vendor-service {@code
 * identity} plus the curated, data-source-level {@code displayName} that the UI renders in the
 * expanded data-source row.
 *
 * <p>The two fields sit at different cardinalities on purpose: many data sources can share one
 * vendor service (the cross-product merge), so each carries its own {@code displayName} while
 * sharing an identical {@link VendorResolution} {@code identity}. Keeping {@code displayName} here
 * — rather than on {@link VendorResolution} — is what lets the bundle-integrity check in {@code
 * VendorAggregator} keep comparing identities by value without two merged data sources looking
 * inconsistent.
 *
 * <p>Use {@link #unknown()} for unmapped triplets: its {@code identity} is the {@link
 * VendorResolution#unknown()} singleton and its {@code displayName} is the fixed label {@code
 * "Unknown"} (never the raw {@code sourceValue}).
 */
public record DataSourceResolution(VendorResolution identity, String displayName) {

  static final String FIELD_IDENTITY = "identity";
  static final String FIELD_DISPLAY_NAME = "displayName";

  private static final String UNKNOWN_DISPLAY_NAME = "Unknown";

  private static final DataSourceResolution UNKNOWN =
      new DataSourceResolution(VendorResolution.unknown(), UNKNOWN_DISPLAY_NAME);

  public DataSourceResolution {
    Objects.requireNonNull(identity, FIELD_IDENTITY);
    Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
  }

  public static DataSourceResolution unknown() {
    return UNKNOWN;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=DataSourceResolutionTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/mapping/DataSourceResolution.java \
        src/test/java/com/rapid7/integrationregistry/mapping/DataSourceResolutionTest.java
git commit -m "feat: add DataSourceResolution value type (identity + curated displayName)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Atomic snapshot-contract migration + curated propagation

Changing `VendorMappingSnapshot.lookup(...)`'s return type breaks every implementation, the parser, the aggregator, both test doubles, and all callers at once — Java will not compile a partial change. This whole task is therefore one compile unit and one commit. The "red" state is a **compile failure** until every file is migrated; the "green" state is `./mvnw verify` passing.

Work the steps in order. Do not run a per-step test until Step 13 — the module will not compile mid-task (this is expected).

**Files:**
- Modify (main): `mapping/VendorMappingSnapshot.java`, `mapping/MapBackedVendorMappingSnapshot.java`, `mapping/loader/VendorMappingSnapshotHolder.java`, `mapping/loader/LoggingVendorMappingSnapshot.java`, `mapping/BundleParser.java`, `aggregator/VendorAggregator.java`, `aggregator/ResolvedInstance.java`, `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`
- Modify (test): `mapping/MapBackedSnapshotBuilder.java`, `testsupport/StubVendorMappingSnapshot.java`, `mapping/MapBackedVendorMappingSnapshotTest.java`, `mapping/MvpSeedBundleTest.java`, `mapping/BundleParserTest.java`, `mapping/loader/LoggingVendorMappingSnapshotTest.java`, `mapping/loader/VendorMappingSnapshotHolderTest.java`, `mapping/loader/S3VendorMappingBundleLoaderTest.java`, `mapping/loader/VendorMappingBootIntegrationTest.java`, `aggregator/VendorAggregatorTest.java`

**Interfaces:**
- Consumes: `DataSourceResolution` from Task 1 (`identity()`, `displayName()`, `unknown()`).
- Produces:
  - `DataSourceResolution VendorMappingSnapshot.lookup(ProductName, SourceType, String)`
  - `MapBackedSnapshotBuilder.map(ProductName, SourceType, String sourceValue, VendorResolution identity)` — retained 4-arg overload; defaults `displayName` to `identity.vendorServiceName()`.
  - `MapBackedSnapshotBuilder.map(ProductName, SourceType, String sourceValue, VendorResolution identity, String displayName)` — new 5-arg overload for tests asserting on display names.
  - `StubVendorMappingSnapshot.returning(String version, DataSourceResolution resolution)` and `returningUnknown(String version)` (unchanged signature; now produces `DataSourceResolution`).

#### Main-source migration

- [ ] **Step 1: Change the interface return type**

In `mapping/VendorMappingSnapshot.java`, change the `lookup` declaration and its `@return` javadoc line.

Replace:
```java
   * @return resolution result for known triplets, or {@link VendorResolution#unknown()} for
   *     unmapped triplets — never null, never throws on unmapped input.
   * @throws NullPointerException if any argument is null
   */
  VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue);
```
With:
```java
   * @return resolution result for known triplets, or {@link DataSourceResolution#unknown()} for
   *     unmapped triplets — never null, never throws on unmapped input.
   * @throws NullPointerException if any argument is null
   */
  DataSourceResolution lookup(ProductName productName, SourceType sourceType, String sourceValue);
```

(`DataSourceResolution` is in the same package — no import needed.)

- [ ] **Step 2: Migrate `MapBackedVendorMappingSnapshot`**

In `mapping/MapBackedVendorMappingSnapshot.java`:

Replace the field + constructor param + lookup body. Change:
```java
  private final Map<TripletKey, VendorResolution> index;
  private final String mappingVersion;

  MapBackedVendorMappingSnapshot(Map<TripletKey, VendorResolution> index, String mappingVersion) {
    Objects.requireNonNull(index, FIELD_INDEX);
    this.index = Map.copyOf(index);
    this.mappingVersion = Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
  }

  @Override
  public VendorResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    // Null validation lives in TripletKey's compact constructor (same FIELD_*
    // messages); duplicating the guards here would just deepen the stack frame.
    return index.getOrDefault(
        new TripletKey(productName, sourceType, sourceValue), VendorResolution.unknown());
  }
```
To:
```java
  private final Map<TripletKey, DataSourceResolution> index;
  private final String mappingVersion;

  MapBackedVendorMappingSnapshot(
      Map<TripletKey, DataSourceResolution> index, String mappingVersion) {
    Objects.requireNonNull(index, FIELD_INDEX);
    this.index = Map.copyOf(index);
    this.mappingVersion = Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
  }

  @Override
  public DataSourceResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    // Null validation lives in TripletKey's compact constructor (same FIELD_*
    // messages); duplicating the guards here would just deepen the stack frame.
    return index.getOrDefault(
        new TripletKey(productName, sourceType, sourceValue), DataSourceResolution.unknown());
  }
```

- [ ] **Step 3: Migrate `VendorMappingSnapshotHolder`**

In `mapping/loader/VendorMappingSnapshotHolder.java`, add the import and change the `lookup` return type.

Add import (alphabetical, after the `ProductName` import block):
```java
import com.rapid7.integrationregistry.mapping.DataSourceResolution;
```
Change:
```java
  @Override
  public VendorResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    return current().lookup(productName, sourceType, sourceValue);
  }
```
To:
```java
  @Override
  public DataSourceResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    return current().lookup(productName, sourceType, sourceValue);
  }
```
The existing `import com.rapid7.integrationregistry.mapping.VendorResolution;` is now unused — remove it (PMD UnusedImports).

- [ ] **Step 4: Migrate `LoggingVendorMappingSnapshot`**

In `mapping/loader/LoggingVendorMappingSnapshot.java`, add the import, change the return type, and rewrite the unknown check + its comment.

Add import:
```java
import com.rapid7.integrationregistry.mapping.DataSourceResolution;
```
Replace:
```java
  // Identity check on VendorResolution.unknown(): the snapshot returns the
  // unknown() singleton for unmapped triplets, so reference equality is
  // sufficient and avoids a 5-field record-equals on the per-request hot path.
  // PMD's CompareObjectsWithEquals can't see the singleton invariant, so we
  // suppress locally.
  @SuppressWarnings("PMD.CompareObjectsWithEquals")
  @Override
  public VendorResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    VendorResolution resolution = delegate.lookup(productName, sourceType, sourceValue);
    if (resolution == VendorResolution.unknown()) {
```
With:
```java
  // Identity check on VendorResolution.unknown(): the snapshot returns a
  // DataSourceResolution whose identity is the unknown() singleton for unmapped
  // triplets, so reference equality on the identity is sufficient and avoids a
  // record-equals on the per-request hot path. PMD's CompareObjectsWithEquals
  // can't see the singleton invariant, so we suppress locally.
  @SuppressWarnings("PMD.CompareObjectsWithEquals")
  @Override
  public DataSourceResolution lookup(
      ProductName productName, SourceType sourceType, String sourceValue) {
    DataSourceResolution resolution = delegate.lookup(productName, sourceType, sourceValue);
    if (resolution.identity() == VendorResolution.unknown()) {
```
Keep the `import ...VendorResolution;` — it is still used (the `VendorResolution.unknown()` reference). Leave the rest of the method (the `log.warn(...)` body and `return resolution;`) unchanged.

- [ ] **Step 5: Migrate `BundleParser.buildSnapshot`**

In `mapping/BundleParser.java`, change the index map type and the per-data-source body to read `display_name` and store a `DataSourceResolution`.

Replace:
```java
  private VendorMappingSnapshot buildSnapshot(JsonNode tree) {
    String mappingVersion = tree.at("/metadata/mapping_version").asString();
    Map<Object, VendorResolution> index = new HashMap<>();
```
With:
```java
  private VendorMappingSnapshot buildSnapshot(JsonNode tree) {
    String mappingVersion = tree.at("/metadata/mapping_version").asString();
    Map<Object, DataSourceResolution> index = new HashMap<>();
```

Replace:
```java
          String sourceValue = ds.get("source_value").asString();
          VendorResolution resolution =
              new VendorResolution(serviceId, serviceName, category, vendorId, vendorName);
          index.put(
              MapBackedVendorMappingSnapshot.key(product, sourceType, sourceValue), resolution);
```
With:
```java
          String sourceValue = ds.get("source_value").asString();
          // display_name is schema-required (minLength 1) and validation has already run by the
          // time buildSnapshot is reached, so the node is present and non-blank — no fallback.
          String displayName = ds.get("display_name").asString();
          VendorResolution identity =
              new VendorResolution(serviceId, serviceName, category, vendorId, vendorName);
          index.put(
              MapBackedVendorMappingSnapshot.key(product, sourceType, sourceValue),
              new DataSourceResolution(identity, displayName));
```

Update the raw-type bridge comment/type at the end of the method. Replace:
```java
    @SuppressWarnings({"unchecked", "rawtypes"})
    Map typedIndex = index;
    return new MapBackedVendorMappingSnapshot(typedIndex, mappingVersion);
```
With (the comment above it referencing `Map<Object, ...>` stays accurate; only confirm the bridge still compiles against the new `Map<TripletKey, DataSourceResolution>` constructor — it does, the raw `Map` erases both):
```java
    @SuppressWarnings({"unchecked", "rawtypes"})
    Map typedIndex = index;
    return new MapBackedVendorMappingSnapshot(typedIndex, mappingVersion);
```
(No textual change needed here — left explicit so the implementer confirms the bridge is intentional, not stale.) `DataSourceResolution` is same-package — no import. The `VendorResolution` import stays (still used for `identity`).

- [ ] **Step 6: Migrate `VendorAggregator.resolveOne`**

In `aggregator/VendorAggregator.java`, add the import:
```java
import com.rapid7.integrationregistry.mapping.DataSourceResolution;
```
Replace the entire `resolveOne` body's mapped + unmapped branches. Change:
```java
    if (productEnum.isPresent() && sourceTypeEnum.isPresent()) {
      VendorResolution resolution =
          snapshot.lookup(productEnum.get(), sourceTypeEnum.get(), sourceValue);
      String dataSourceId =
          DataSourceIdMinter.mint(rawProductName, sourceTypeEnum.get(), sourceValue);
      if (Objects.equals(resolution, VendorResolution.unknown())) {
        warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
      }
      // displayName == sourceValue is intentional per spec §displayName gap (deferred-scope ruling)
      return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
    }

    // Unmappable enum strings — route through the same unknown path.
    VendorResolution resolution = VendorResolution.unknown();
    String dataSourceId = DataSourceIdMinter.mint(rawProductName, rawSourceType, sourceValue);
    warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
    return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
```
With:
```java
    if (productEnum.isPresent() && sourceTypeEnum.isPresent()) {
      DataSourceResolution resolution =
          snapshot.lookup(productEnum.get(), sourceTypeEnum.get(), sourceValue);
      String dataSourceId =
          DataSourceIdMinter.mint(rawProductName, sourceTypeEnum.get(), sourceValue);
      if (Objects.equals(resolution.identity(), VendorResolution.unknown())) {
        warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
      }
      // displayName is the curated, data-source-level bundle label (or "Unknown" for unmapped
      // triplets via DataSourceResolution.unknown()) — never the raw sourceValue.
      return new ResolvedInstance(
          n, dataSourceId, resolution.identity(), resolution.displayName());
    }

    // Unmappable enum strings — route through the same unknown path. displayName is the fixed
    // "Unknown" label (DataSourceResolution.unknown()), never the raw sourceValue.
    DataSourceResolution resolution = DataSourceResolution.unknown();
    String dataSourceId = DataSourceIdMinter.mint(rawProductName, rawSourceType, sourceValue);
    warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
    return new ResolvedInstance(
        n, dataSourceId, resolution.identity(), resolution.displayName());
```
`VendorResolution` import stays (used in `VendorResolution.unknown()` and elsewhere in the class).

- [ ] **Step 7: Correct the `ResolvedInstance` javadoc**

In `aggregator/ResolvedInstance.java`, replace the second javadoc paragraph. Change:
```java
 * <p>Per the spec's {@code displayName} gap: {@code displayName} is the raw {@code sourceValue} for
 * both mapped and unmapped triplets until the snapshot surfaces curated bundle {@code display_name}
 * values.
```
With:
```java
 * <p>{@code displayName} carries the curated, data-source-level {@code display_name} from the
 * vendor-mapping bundle for mapped triplets, and the fixed label {@code "Unknown"} for unmapped
 * triplets (via {@link com.rapid7.integrationregistry.mapping.DataSourceResolution#unknown()}). It
 * is never the raw {@code sourceValue}.
```
(No field/constructor change — `ResolvedInstance` still holds a `displayName` String.)

- [ ] **Step 8: Correct the `mvp-seed.yaml` header comment**

In `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`, replace the first comment block. Change:
```yaml
# yaml-language-server: $schema=../schema/v1.json
# Note: `display_name` fields are required by the JSON Schema (v1) for human
# readability of the bundle source, but the parser does not propagate them
# into VendorResolution — that responsibility lives at presentation layers.
```
With:
```yaml
# yaml-language-server: $schema=../schema/v1.json
# Note: `display_name` fields are required by the JSON Schema (v1). The parser
# propagates each one into the snapshot via DataSourceResolution (identity +
# displayName); the aggregator surfaces it as the expanded data-source row label.
```
Leave the second note (about `source_value` being komand's slug) unchanged.

#### Test-double migration

- [ ] **Step 9: Migrate `StubVendorMappingSnapshot`**

In `testsupport/StubVendorMappingSnapshot.java`, swap the resolution type to `DataSourceResolution` throughout. Replace the whole file body below the package line with:

```java
package com.rapid7.integrationregistry.testsupport;

import com.rapid7.integrationregistry.mapping.DataSourceResolution;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;

/**
 * Test stub of {@link VendorMappingSnapshot} for unit tests in the loader package that need a
 * snapshot without spinning up the real {@code MapBackedVendorMappingSnapshot} (e.g. listener
 * tests, holder tests, decorator tests, health-indicator tests).
 *
 * <p>Two factories: {@link #returningUnknown(String)} for tests that only care about the {@code
 * mappingVersion()} surface, and {@link #returning(String, DataSourceResolution)} for tests that
 * need a specific resolution value back from {@code lookup(...)}.
 */
public final class StubVendorMappingSnapshot {

  private StubVendorMappingSnapshot() {}

  /**
   * Stub whose {@code lookup(...)} always returns {@link DataSourceResolution#unknown()} — useful
   * for tests that only assert on {@code mappingVersion()} or on side effects of the lookup call.
   */
  public static VendorMappingSnapshot returningUnknown(String version) {
    return returning(version, DataSourceResolution.unknown());
  }

  /**
   * Stub whose {@code lookup(...)} always returns the supplied resolution regardless of arguments.
   * The caller controls both the version and the resolution.
   */
  public static VendorMappingSnapshot returning(String version, DataSourceResolution resolution) {
    return new VendorMappingSnapshot() {
      @Override
      public DataSourceResolution lookup(
          ProductName productName, SourceType sourceType, String sourceValue) {
        return resolution;
      }

      @Override
      public String mappingVersion() {
        return version;
      }
    };
  }
}
```

- [ ] **Step 10: Migrate `MapBackedSnapshotBuilder` (retain 4-arg, add 5-arg)**

In `mapping/MapBackedSnapshotBuilder.java`, change the index value type and `map(...)` so the 4-arg form (used by ~18 don't-care call sites) keeps compiling by defaulting `displayName` to the identity's vendor-service name, and add a 5-arg form for display-name-asserting tests.

Replace:
```java
  private final Map<Object, VendorResolution> index = new HashMap<>();
```
With:
```java
  private final Map<Object, DataSourceResolution> index = new HashMap<>();
```

Replace:
```java
  public MapBackedSnapshotBuilder map(
      ProductName productName,
      SourceType sourceType,
      String sourceValue,
      VendorResolution resolution) {
    Objects.requireNonNull(resolution, "resolution");
    index.put(MapBackedVendorMappingSnapshot.key(productName, sourceType, sourceValue), resolution);
    return this;
  }
```
With:
```java
  /**
   * Map a triplet, defaulting the data-source {@code displayName} to the identity's vendor-service
   * name — convenience for tests that do not assert on display names.
   */
  public MapBackedSnapshotBuilder map(
      ProductName productName,
      SourceType sourceType,
      String sourceValue,
      VendorResolution identity) {
    Objects.requireNonNull(identity, "identity");
    return map(productName, sourceType, sourceValue, identity, identity.vendorServiceName());
  }

  /** Map a triplet with an explicit curated {@code displayName}. */
  public MapBackedSnapshotBuilder map(
      ProductName productName,
      SourceType sourceType,
      String sourceValue,
      VendorResolution identity,
      String displayName) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(displayName, "displayName");
    index.put(
        MapBackedVendorMappingSnapshot.key(productName, sourceType, sourceValue),
        new DataSourceResolution(identity, displayName));
    return this;
  }
```
No import needed: `MapBackedSnapshotBuilder` is already in package `com.rapid7.integrationregistry.mapping`, so `DataSourceResolution` resolves directly. Keep the `VendorResolution` references as-is.

Update the class javadoc example block that shows `.map(...)` returning a `VendorResolution` — it still compiles (4-arg form retained), so no change required.

#### Test-caller migration

- [ ] **Step 11: Migrate `MapBackedVendorMappingSnapshotTest`**

In `mapping/MapBackedVendorMappingSnapshotTest.java`, the snapshot now returns `DataSourceResolution`. Update the helper types and assertions.

Replace:
```java
  private static VendorResolution sampleResolution() {
    return new VendorResolution(
        "microsoft-defender", "Microsoft Defender", VendorCategory.EDR, "microsoft", "Microsoft");
  }

  private static Map<Object, VendorResolution> oneEntryIndex(VendorResolution resolution) {
    // The TripletKey type is private to MapBackedVendorMappingSnapshot;
    // construct keys via the package-private key(...) factory.
    Map<Object, VendorResolution> raw = new HashMap<>();
    raw.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint"),
        resolution);
    return raw;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static MapBackedVendorMappingSnapshot construct(
      Map<Object, VendorResolution> rawIndex, String version) {
    // The constructor's Map<TripletKey, VendorResolution> parameter is reachable
    // from this same package; use a raw type to bridge our test-side Object key map
    // (which actually holds TripletKey instances minted by key(...)).
    return new MapBackedVendorMappingSnapshot((Map) rawIndex, version);
  }
```
With:
```java
  private static DataSourceResolution sampleResolution() {
    return new DataSourceResolution(
        new VendorResolution(
            "microsoft-defender",
            "Microsoft Defender",
            VendorCategory.EDR,
            "microsoft",
            "Microsoft"),
        "Microsoft Defender for Endpoint");
  }

  private static Map<Object, DataSourceResolution> oneEntryIndex(DataSourceResolution resolution) {
    // The TripletKey type is private to MapBackedVendorMappingSnapshot;
    // construct keys via the package-private key(...) factory.
    Map<Object, DataSourceResolution> raw = new HashMap<>();
    raw.put(
        MapBackedVendorMappingSnapshot.key(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint"),
        resolution);
    return raw;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static MapBackedVendorMappingSnapshot construct(
      Map<Object, DataSourceResolution> rawIndex, String version) {
    // The constructor's Map<TripletKey, DataSourceResolution> parameter is reachable
    // from this same package; use a raw type to bridge our test-side Object key map
    // (which actually holds TripletKey instances minted by key(...)).
    return new MapBackedVendorMappingSnapshot((Map) rawIndex, version);
  }
```
Then update the three `lookup(...)` result types and the unknown/other-resolution references:
- In `lookup_shouldReturnIndexedResolution_whenTripletPresent`, `lookup_shouldReturnSameInstance_acrossRepeatedCalls`: change local var type `VendorResolution` → `DataSourceResolution`.
- In `lookup_shouldReturnUnknownResolution_whenTripletAbsent`: change `VendorResolution result` → `DataSourceResolution result` and `assertThat(result).isSameAs(VendorResolution.unknown())` → `assertThat(result).isSameAs(DataSourceResolution.unknown())`.
- In `constructor_shouldDefensivelyCopy_whenMutatingSourceMapAfterConstruction`: change `VendorResolution otherResolution = new VendorResolution("jira", ...)` → `DataSourceResolution otherResolution = new DataSourceResolution(new VendorResolution("jira", "Jira", VendorCategory.ITSM, "atlassian", "Atlassian"), "Jira")`, change `VendorResolution result` → `DataSourceResolution result`, and the final assertion to `isSameAs(DataSourceResolution.unknown())`.

(`DataSourceResolution` is same-package — no import needed.)

- [ ] **Step 12: Migrate the remaining test callers**

Apply these exact edits:

**`mapping/MvpSeedBundleTest.java`** — three resolving tests use `VendorResolution resolution = snapshot.lookup(...)` then assert `.vendorServiceId()` etc., and one asserts `isSameAs(VendorResolution.unknown())`:
- In `mvpSeed_shouldResolveDefenderViaIDR_toMicrosoftDefender`, `mvpSeed_shouldResolveDefenderViaICON_toMicrosoftDefender`, `mvpSeed_shouldResolveJiraViaICON_toJira`: change the local var type to `DataSourceResolution resolution = ...` and change each `resolution.vendorServiceId()` → `resolution.identity().vendorServiceId()` (and likewise `.vendorServiceName()`, `.vendorCategory()`, `.vendorId()`, `.vendorName()`). Add an assertion on the curated label in each:
  - IDR Defender: `assertThat(resolution.displayName()).isEqualTo("Microsoft Defender for Endpoint");`
  - ICON Defender: `assertThat(resolution.displayName()).isEqualTo("Microsoft Defender");`
  - Jira: `assertThat(resolution.displayName()).isEqualTo("Jira");`
- In `mvpSeed_shouldReturnUnknown_forUnmappedTriplet`: change `VendorResolution resolution` → `DataSourceResolution resolution` and `isSameAs(VendorResolution.unknown())` → `isSameAs(DataSourceResolution.unknown())`.

**`mapping/BundleParserTest.java`** — in `parse_shouldReturnSnapshot_whenValidMinimalBundle`: change `VendorResolution resolution = snapshot.lookup(...)` → `DataSourceResolution resolution = ...`, prefix the five identity assertions with `.identity()` (e.g. `resolution.identity().vendorServiceId()`), and add `assertThat(resolution.displayName()).isEqualTo("Microsoft Defender for Endpoint");` (the `valid-minimal.yaml` fixture's curated value). In `parse_shouldReturnSnapshotWithUnknownLookup_whenTripletAbsent` and `parse_shouldReturnSnapshot_whenStreamProvidedAsByteArray`: change `VendorResolution result`/inline `lookup(...)` assertions to compare against `DataSourceResolution.unknown()` (e.g. `assertThat(result).isSameAs(DataSourceResolution.unknown())` and the byte-array test's `assertThat(snapshot.lookup(...)).isSameAs(DataSourceResolution.unknown())`).

**`mapping/loader/LoggingVendorMappingSnapshotTest.java`**:
- `lookup_shouldLogWarn_whenUnderlyingReturnsUnknown`: change the stub to `StubVendorMappingSnapshot.returning("v1.0.0", DataSourceResolution.unknown())`, the result var to `DataSourceResolution result`, and `assertThat(result).isSameAs(VendorResolution.unknown())` → `isSameAs(DataSourceResolution.unknown())`.
- `lookup_shouldNotLog_whenUnderlyingReturnsKnown`: keep `known` as a `VendorResolution`, wrap it for the stub — `DataSourceResolution knownDsr = new DataSourceResolution(known, "Microsoft Defender");` then `StubVendorMappingSnapshot.returning("v1.0.0", knownDsr)`; change result var to `DataSourceResolution result` and assert `assertThat(result).isSameAs(knownDsr);`.
- `mappingVersion_shouldDelegate_always`: change `VendorResolution.unknown()` → `DataSourceResolution.unknown()`.
- Add `import com.rapid7.integrationregistry.mapping.DataSourceResolution;` (keep the `VendorResolution` import — still used for `known`).

**`mapping/loader/VendorMappingSnapshotHolderTest.java`**:
- `lookup_shouldDelegate_whenSet`: change `expected` to a `DataSourceResolution` — `DataSourceResolution expected = new DataSourceResolution(new VendorResolution("microsoft-defender","Microsoft Defender",VendorCategory.EDR,"microsoft","Microsoft"), "Microsoft Defender for Endpoint");`; change the actual var to `DataSourceResolution actual` and keep `assertThat(actual).isSameAs(expected);`.
- Lines using `StubVendorMappingSnapshot.returning(version, VendorResolution.unknown())` (mappingVersion/set/isLoaded tests): change `VendorResolution.unknown()` → `DataSourceResolution.unknown()`.
- Add `import com.rapid7.integrationregistry.mapping.DataSourceResolution;` (keep `VendorResolution`/`VendorCategory` imports — still used to build `expected`).

**`mapping/loader/VendorMappingBootIntegrationTest.java`** — in `snapshot_shouldResolveAllFourMvpTriplets`: change each `VendorResolution idrDefender/iconDefender/jira/mystery = vendorMappingSnapshot.lookup(...)` to `DataSourceResolution ...`, prefix identity assertions with `.identity()` (e.g. `idrDefender.identity().vendorServiceId()`), change `isSameAs(VendorResolution.unknown())` → `isSameAs(DataSourceResolution.unknown())`, and add `assertThat(idrDefender.displayName()).isEqualTo("Microsoft Defender for Endpoint");` after the IDR block. Add `import com.rapid7.integrationregistry.mapping.DataSourceResolution;` (keep `VendorResolution` — still imported; if it becomes unused after edits, remove it to satisfy PMD).

**`mapping/loader/S3VendorMappingBundleLoaderTest.java`** — in `load_shouldReadFromDisk_whenCacheExists`: the chained call `snapshot.lookup(...).vendorServiceId()` becomes `snapshot.lookup(...).identity().vendorServiceId()`. No import change.

- [ ] **Step 13: Migrate `VendorAggregatorTest` (flip display-name assertions)**

In `aggregator/VendorAggregatorTest.java`, the ~18 `.map(...)` calls that pass only `MS_DEFENDER`/`msSentinel`/`jira`/etc. keep compiling unchanged (the 4-arg overload defaults `displayName`). Only the two display-name-asserting tests change.

In `toVendorServiceDetail_shouldReturnDetailWithDataSources_whenFound`, switch both `.map(...)` calls to the 5-arg overload so the two data sources carry distinct curated labels:
- Change the IDR mapping to:
  ```java
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER,
                  "Microsoft Defender for Endpoint")
  ```
- Change the ICON mapping to:
  ```java
              .map(
                  ProductName.INSIGHT_CONNECT,
                  SourceType.PLUGIN_NAME,
                  "microsoft-defender",
                  MS_DEFENDER,
                  "Microsoft Defender")
  ```
- Flip the two display-name assertions:
  - `assertThat(idrDs.displayName()).isEqualTo("microsoft-defender-endpoint");` → `assertThat(idrDs.displayName()).isEqualTo("Microsoft Defender for Endpoint");`
  - `assertThat(iconDs.displayName()).isEqualTo("microsoft-defender");` → `assertThat(iconDs.displayName()).isEqualTo("Microsoft Defender");`

In `toVendorServiceDetail_shouldResolveUnknownVendorServiceId_whenUnmappedInstancesPresent` (unmapped path — snapshot maps nothing, so `DataSourceResolution.unknown()` drives the label), update the comment and flip the displayName assertion:
- Change comment `// Each DS preserves its raw triplet in data_source_id and uses sourceValue as displayName` → `// Each DS preserves its raw triplet in data_source_id; unmapped displayName is the fixed "Unknown"`.
- Change:
  ```java
      assertThat(detail.dataSources())
          .extracting(DataSourceDetail::displayName)
          .containsExactlyInAnyOrder("new-product-a", "new-product-b", "new-product-c");
  ```
  To:
  ```java
      assertThat(detail.dataSources())
          .extracting(DataSourceDetail::displayName)
          .containsExactly("Unknown", "Unknown", "Unknown");
  ```

- [ ] **Step 14: Format, compile, and run the full suite (the green gate)**

Ensure Docker is running (Valkey Testcontainers). Then:

Run:
```bash
./mvnw -q spotless:apply
./mvnw verify
```
Expected: BUILD SUCCESS. ArchUnit, PMD, Spotless-check, and all JUnit tests pass. If compilation fails, a caller was missed — grep for stragglers:
```bash
grep -rn "VendorResolution .*= .*\.lookup(\|\.lookup([^)]*));" src --include=*.java
```
and fix any remaining `lookup(...)` whose result is treated as a `VendorResolution`.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "feat: propagate curated display_name to data-source rows

Change VendorMappingSnapshot.lookup to return DataSourceResolution
(identity + curated displayName). BundleParser reads the schema-required
display_name; VendorAggregator.resolveOne stops substituting the raw
sourceValue and uses the curated label (or fixed 'Unknown' for unmapped
triplets). VendorResolution identity and its equals()-based bundle-integrity
check are untouched. Migrates all snapshot impls, test doubles, and callers.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: End-to-end + schema coverage hardening

Adds the coverage that proves the user-facing fix and locks the fail-fast contract. Both additions are independently reviewable and depend on Task 2 being green.

**Files:**
- Create: `src/test/resources/vendor-mapping/invalid-missing-display-name.json`
- Modify: `mapping/BundleSchemaTest.java`, `integration/ReadPathIntegrationTest.java`

**Interfaces:**
- Consumes: the migrated read path (Task 2); `BundleSchemaResources.validateFixture(...)` (existing helper); `multi-service-test.yaml` (existing bundle with curated labels "Microsoft Defender for Endpoint" / "Microsoft Defender").

- [ ] **Step 1: Write the schema negative fixture**

Create `src/test/resources/vendor-mapping/invalid-missing-display-name.json` (a data source omitting `display_name` — the schema marks it `required`):

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
                "source_value": "microsoft-defender-endpoint"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 2: Write the failing schema test**

In `mapping/BundleSchemaTest.java`, add this test method (mirror the existing `validate_shouldReject_whenSourceValueIsEmpty` idiom — find the exact assertion style already in the file and match it). Place it after `validate_shouldReject_whenSourceValueIsEmpty`:

```java
  @Test
  void validate_shouldReject_whenDisplayNameMissing() throws IOException {
    // Arrange / Act
    Set<ValidationMessage> errors = validateFixture("invalid-missing-display-name.json");

    // Assert — display_name is required on every data source
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getMessage().contains("display_name"));
  }
```

> Note: confirm the helper name (`validateFixture`) and the `errors` assertion shape by reading the sibling reject-tests at the top of `BundleSchemaTest.java` before writing — match whatever idiom is already there (some validators surface the missing-property name via `getMessage()`, others via `getInstanceLocation()`; use the same `.anyMatch(...)` predicate style the existing `required`-rejection tests use, e.g. `validate_shouldReject_whenApiVersionMissing`).

- [ ] **Step 3: Run the schema test to verify it passes**

Run: `./mvnw -q test -Dtest=BundleSchemaTest`
Expected: PASS (existing tests plus the new one). If the predicate doesn't match the validator's actual message text, adjust the `.anyMatch(...)` to mirror the closest existing `required`-rejection test (e.g. assert on `getInstanceLocation().toString().contains("data_sources")` if `getMessage()` doesn't carry the property name).

- [ ] **Step 4: Write the failing end-to-end detail-route test**

In `integration/ReadPathIntegrationTest.java`, add a test that hits `GET /vendor-services/microsoft-defender` and asserts the curated `display_name` reaches the wire. The `multi-service-test.yaml` bundle (already staged by `ReadPathTestSupport`) maps the two Defender data sources to "Microsoft Defender for Endpoint" (IDR) and "Microsoft Defender" (ICON).

First add the import (alphabetical, with the other `controller.dto` imports):
```java
import com.rapid7.integrationregistry.controller.dto.DataSourceDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
```

Then add the test method (place after `getVendorDetail_shouldReturnNestedProjectionWithRollups_whenVendorHasMultipleServices`):

```java
  @Test
  void getVendorServiceDetail_shouldRenderCuratedDisplayNames_whenDefenderHasTwoDataSources() {
    // Arrange — Microsoft Defender via IDR + ICON. The two triplets resolve to the SAME vendor
    // service but distinct data sources with distinct curated display names in the test bundle.
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
            integration(
                INSIGHT_CONNECT, "plugin_name", "microsoft-defender", IntegrationStatus.HEALTHY)));

    // Act — assert 200 explicitly so a future 404 fails cleanly rather than throwing.
    ResponseEntity<VendorServiceDetailResponse> response =
        get("/integration-registry/v1/vendor-services/microsoft-defender")
            .retrieve()
            .toEntity(VendorServiceDetailResponse.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    VendorServiceDetailResponse body = response.getBody();
    assertThat(body).isNotNull();

    // Assert — the expanded data-source rows carry the curated labels, NOT the raw source slugs.
    assertThat(body.vendorServiceId()).isEqualTo("microsoft-defender");
    assertThat(body.dataSources())
        .extracting(DataSourceDto::displayName)
        .containsExactlyInAnyOrder("Microsoft Defender for Endpoint", "Microsoft Defender");
  }
```

- [ ] **Step 5: Run the read-path suite to verify it passes**

Ensure Docker is running. Run: `./mvnw -q test -Dtest=ReadPathIntegrationTest`
Expected: PASS (existing six scenarios plus the new detail-route test). The new test would have shown `"microsoft-defender-endpoint"` / `"microsoft-defender"` before Task 2 — now it shows the curated labels.

- [ ] **Step 6: Format, full verify, and commit**

Run:
```bash
./mvnw -q spotless:apply
./mvnw verify
```
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "test: cover curated display_name end-to-end and missing display_name rejection

Adds a /vendor-services/{id} read-path assertion proving curated labels
reach the wire (the only route carrying display_name, previously untested
end-to-end), and a BundleSchemaTest negative case locking the schema's
required display_name (fail-fast at bundle load).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Notes for the implementer

- **Why Task 2 is one big commit:** changing an interface method's return type is atomic in Java — the interface, all implementations, and all callers must change together to compile. There is no smaller unit that compiles. Do not try to split it.
- **The `unknown()` reference-equality invariant:** `MapBackedVendorMappingSnapshot.lookup` returns the `DataSourceResolution.unknown()` singleton on a miss, and that singleton's `identity()` is the `VendorResolution.unknown()` singleton. So `LoggingVendorMappingSnapshot`'s `resolution.identity() == VendorResolution.unknown()` and the aggregator's `Objects.equals(resolution.identity(), VendorResolution.unknown())` both detect unmapped triplets exactly as before.
- **No production fallback:** for mapped triplets the label is the bundle's `display_name` (fail-fast at schema load if absent); for unmapped triplets it is the fixed `"Unknown"`. The raw `sourceValue` is never used as a label anywhere in main source after this change. (The test builder's 4-arg convenience default uses the vendor-service name, which is test-only scaffolding and never reached in production.)
