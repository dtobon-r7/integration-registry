# Design — Vendor mapping contract layer

**Work plan**: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/01-vendor-mapping-contract.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/01-vendor-mapping-contract.md)
**Track**: 04 — Vendor mapping bundle and snapshot
**Branch**: `uiv/track-04/01-vendor-mapping-contract`
**Date**: 2026-05-26

---

## Outcome

Land the **stable surface area** of the vendor-mapping concern at stable repo paths so downstream tracks (T08 aggregator, T11 CI/publish pipeline) can stagger-start. Two artifacts:

1. **A bundle JSON Schema** (Draft 2020-12) describing `apiVersion: registry.rapid7.com/v1`, `kind: VendorMapping` documents, published at `src/main/resources/vendor-mapping/schema/v1.json`.
2. **A `VendorMappingSnapshot` interface** in `com.rapid7.integrationregistry.mapping`, exposing `lookup(productName, sourceType, sourceValue) → VendorResolution` and `mappingVersion() → String`, with the `VendorResolution` record carrying the five resolved fields and a static `unknown()` factory returning the synthetic triplet.

This is the stagger-point deliverable named by `dependencies.md`: *"T04 publishes the bundle JSON Schema and the `VendorMappingSnapshot` interface as its first deliverables."* Once shipped, T08 starts against the snapshot interface, T11 starts against the JSON Schema, and Plan 02 (parser + MVP seed) starts against both.

## Architecture

The contract layer lives under `com.rapid7.integrationregistry.mapping`:

- One interface — `VendorMappingSnapshot`
- One record — `VendorResolution` (with `unknown()` factory)
- Three enums — `VendorCategory`, `SourceType`, `ProductName`
- One JSON Schema document — `src/main/resources/vendor-mapping/schema/v1.json`

Nothing in this package depends on any other internal Registry layer. The package depends only on the JDK. No Spring annotations, no Jackson annotations, no JSON-Schema-validator imports in main — the validator is **test scope only**.

ArchUnit's existing `aggregatorLayer_shouldNotDependOnNonMappingLayers` rule (`LayerDependencyRules.java`) already permits the `aggregator → mapping` edge T08 will consume. The other layer rules (controller / coordinator / adapter) already exclude `..mapping..`. No new rules are added by this PR.

This PR ships **types, shapes, and one schema file** only. No behavior. No parsing, no snapshot implementation (Plan 02). No S3 fetch, no readiness gate, no WARN logging on unknown lookups (Plan 03). No CI uniqueness / immutability / deprecation enforcement (T11). No aggregator, coordinator, controller code (T08, T09).

## Components

All Java code under `src/main/java/com/rapid7/integrationregistry/mapping/`.

### Interface — `VendorMappingSnapshot.java`

```java
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

Two-method contract. `lookup` is **total** — never returns null, never throws on unmapped input; the unknown contract is part of the return type, not an exception path. RFC-001 §Vendor mapping → Bundle lifecycle commits to this: *"unknown entries are never dropped"*.

`lookup` parameters use the typed enums (`ProductName`, `SourceType`) — not raw strings — so callers can't accidentally pass a wire-form string that doesn't match a known constant. The `sourceValue` stays a `String` because its values are open-ended (every IDR product type, every ICON plugin name).

No default methods, no nested types, no `@FunctionalInterface` (the interface is a contract for an in-memory snapshot, not a callback).

### Record — `VendorResolution.java`

```java
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

    /**
     * The synthetic resolution returned for unmapped triplets, per RFC-001
     * §Vendor mapping → Bundle lifecycle: {@code vendor_service_id="unknown"},
     * {@code vendor_service_name="Unknown"}, {@code vendor_category="other"},
     * {@code vendor_id="unknown"}, {@code vendor_name="Unknown"}.
     */
    public static VendorResolution unknown() {
        return UNKNOWN;
    }
}
```

Five required fields, all null-checked in the compact constructor with package-private `FIELD_<NAME>` constants — same pattern as `NormalizedIntegration` in track-05/wp-01. The constants are package-private so test classes in the same package can reference them in null-rejection assertions (`.withMessage(VendorResolution.FIELD_VENDOR_ID)`), keeping field renames and test messages locked together. PMD's `AvoidDuplicateLiterals` is the backstop.

The `unknown()` factory returns a **single shared instance** stored in `private static final UNKNOWN`. Records' implicit `equals`/`hashCode` makes two resolutions with the same content equal, but a single instance is clearer and slightly cheaper. The factory is a method (not a public constant) so callers go through one named entry point — `VendorResolution.unknown()` reads as a deliberate fallback.

### Enum — `VendorCategory.java`

```java
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

Seven constants per KB 21.02 — `cloud_provider`, `identity`, `itsm`, `siem`, `edr`, `notification`, `other`. Constructor takes the wire form (the snake-case string the JSON Schema's `category` enum lists); `wireForm()` accessor reads it; `fromWireForm(String)` returns `Optional<VendorCategory>` so callers (Plan 02's parser) can throw a meaningful parse error on a value that should never appear (the schema would have rejected it first).

Linear scan over 7 constants — index map is unjustified at this size; the loop allocates nothing so PMD's `AvoidInstantiatingObjectsInLoops` doesn't fire.

### Enum — `SourceType.java`

```java
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

Four constants per RFC-001 §`source_type` enum.

### Enum — `ProductName.java`

```java
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

Six constants per RFC-001 §Canonical `productName()` values. `SURFACE_COMMAND` carries the **two-word** wire form `"Surface Command"` — the RFC is explicit: *"Strings are display-form … two-word form for Surface Command, matching how the RFC body and dossiers refer to it."*

### `package-info.java` (existing — javadoc broadened)

```java
/**
 * Vendor-mapping read-side contract: the {@link VendorMappingSnapshot} interface,
 * the {@link VendorResolution} record carrying resolved vendor/vendor-service identity,
 * and the closed enums ({@link VendorCategory}, {@link SourceType}, {@link ProductName})
 * referenced by the bundle JSON Schema and by the snapshot lookup API.
 *
 * <p>Implementations of {@code VendorMappingSnapshot} live in this package (Plan 02);
 * no other internal Registry layer may depend on this package other than {@code aggregator}
 * (enforced by ArchUnit).
 */
package com.rapid7.integrationregistry.mapping;
```

Mirrors the `adapter/package-info.java` pattern — broadened from the one-liner to cover the contract surface this PR adds.

## JSON Schema

Single Draft 2020-12 document at `src/main/resources/vendor-mapping/schema/v1.json`. Closed at every object level (`additionalProperties: false`). Validates the **parsed** representation of the YAML bundle — Plan 02 parses YAML to `JsonNode` then runs it through this schema. Test fixtures are JSON, not YAML, on purpose: the schema operates on the parsed tree, and JSON keeps the test stack at this layer Jackson-free.

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
      "pattern": "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
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

Five design notes:

**1. `apiVersion` and `kind` use `const`, not `enum`.** Bumping `apiVersion` to `v2` per RFC-001 §Bundle structure means *a new schema file* (`v2.json`), not editing this one. `const` makes the boundary unmistakable: this schema validates v1 documents, period.

**2. Closed-by-default at every object level.** A bundle with a typo like `data_soruces` or a fabricated field like `vendor.deprecated_at` fails validation. RFC-001's apiVersion-bump-on-breaking-change rule means closed-by-default has zero migration cost.

**3. `services` and `data_sources` are not in the `required` lists.** RFC-001 §Optional fields explicitly says *"a vendor with no services or a service with no data sources is a legal placeholder."* Schema permits omission **and** an explicit `[]`.

**4. The `\|` ban uses `not.pattern`, not a regex range exclusion.** `\\|` is the JSON-Schema regex for a literal pipe; `not: {pattern: "\\|"}` reads as "the string must not contain a pipe." Cleaner than a positive regex like `^[^|]+$`.

**5. The `unknown` slug reservation is *not* enforced here.** RFC-001 §Bundle lifecycle says *"the `unknown` slugs are reserved — bundle CI rejects any `vendors[].id` or `services[].id` equal to `\"unknown\"`."* That's **T11's CI suite**, not the JSON Schema. The schema permits `"unknown"` as a slug because it matches `^[a-z0-9_-]+$`. The fixture suite includes one positive-case test that locks this boundary explicitly.

## Data flow

This PR has no runtime data flow — only the contracts for one. For context, the two flows it enables (delivered by Plans 02 and 03, not here):

```
Boot path (Plan 02 + Plan 03):
  S3 → fetch tarball → cache on disk → unpack YAML
                                          ↓
                          Jackson YAMLFactory → JsonNode tree
                                          ↓
                              Validate against v1.json   ← THIS PLAN ships the schema
                                          ↓ (passes)
                              Build immutable Map-backed
                              VendorMappingSnapshot      ← THIS PLAN ships the interface
                                          ↓
                              Spring bean (impl. of
                              VendorMappingSnapshot)

Read path (T08 aggregator + T09 read API, future):
  Adapter emits NormalizedIntegration with raw SourceIdentifier
                                          ↓
  VendorAggregator (T08) calls
    snapshot.lookup(productName, sourceType, sourceValue)  ← THIS PLAN ships the lookup
                                          ↓
                                  VendorResolution         ← THIS PLAN ships the result type
                                          ↓
                          (known triplet → real fields,
                           unmapped → unknown())
                                          ↓
                          T08 attaches resolved fields to
                          the response; T09 surfaces
                          metadata.mapping_version
```

Three interface-design guarantees protect this flow:

1. **Lookup is total.** `VendorResolution` is non-null on every input. The aggregator never has to handle null returns; the unknown contract is part of the value shape.
2. **`mappingVersion()` is part of the contract, not derived.** T09 reads it directly off the bean Spring exposes; it doesn't have to find the YAML or re-parse it.
3. **Lookup parameters are typed enums for `productName` and `sourceType`.** Aggregator code can't pass a wire-form string that doesn't match a known enum constant — wire-form-to-enum conversion happens once at YAML parse time (Plan 02), not on every lookup call.

## Error handling

This plan ships **no runtime code that can fail** — there are no I/O paths, no network calls, no parsing. The only error paths are construction-time guards on the value types:

| Construction site | Failure mode | Guard |
|---|---|---|
| `new VendorResolution(...)` with any of five fields null | `NullPointerException` with the field name as the message | Compact constructor + `Objects.requireNonNull(field, FIELD_<NAME>)` |
| `VendorCategory.fromWireForm("not-a-real-cat")` | Returns `Optional.empty()` | Linear scan over `values()` |
| `SourceType.fromWireForm("not-a-real-st")` | Returns `Optional.empty()` | Same shape |
| `ProductName.fromWireForm("not-a-real-prod")` | Returns `Optional.empty()` | Same shape |

`VendorMappingSnapshot.lookup` deliberately has **no error mode** in the contract. Implementations (Plan 02) may not throw on unmapped triplets — the unknown contract is part of the return value, not an exception path. This is locked at the interface level so the aggregator (T08) doesn't need to wrap lookup calls in try/catch.

What this contract deliberately does **not** specify:

- **No `mappingVersion()` failure mode.** A snapshot with a null mapping version is incomplete — that's a Plan 02 bug to enforce at construction, not part of this contract. The interface declares `String`, not `Optional<String>`.
- **No bundle-load failures.** Plan 03 owns those (S3 unreachable, validation failure → readiness probe stays down).
- **No schema-validation API.** This plan ships the schema *file*; running it is Plan 02's concern. The validator dependency in `pom.xml` is **test scope only** — production code doesn't import `com.networknt.schema.*`.

## Testing

Five test classes total under `src/test/java/com/rapid7/integrationregistry/mapping/`. JUnit 5 + AssertJ, AAA-structured, `methodName_shouldDoX_whenY()` per [TESTING.md](../../../TESTING.md). No Spring context; JSON fixtures live in `src/test/resources/vendor-mapping/`.

Null-rejection assertions reference the package-private `FIELD_<NAME>` constants on the record under test (e.g., `.withMessage(VendorResolution.FIELD_VENDOR_ID)`), so a field rename and the test stay locked together.

### `VendorResolutionTest.java`

- `constructor_shouldBuildRecord_whenAllFiveFieldsProvided`
- `constructor_shouldThrowNPE_whenVendorServiceIdNull`
- `constructor_shouldThrowNPE_whenVendorServiceNameNull`
- `constructor_shouldThrowNPE_whenVendorCategoryNull`
- `constructor_shouldThrowNPE_whenVendorIdNull`
- `constructor_shouldThrowNPE_whenVendorNameNull`
- `unknown_shouldReturnSyntheticTriplet_whenInvoked` — asserts `vendorServiceId="unknown"`, `vendorServiceName="Unknown"`, `vendorCategory=OTHER`, `vendorId="unknown"`, `vendorName="Unknown"`.
- `unknown_shouldReturnSameInstance_whenInvokedTwice` — asserts `unknown() == unknown()` (single shared instance).

### `VendorCategoryTest.java`

- `values_shouldContainExactlySevenCategories_whenInspected` — regression guard if an eighth category is added without RFC amendment.
- `wireForm_shouldReturnRfcCanonicalString_forEachConstant` — table-driven over all 7 constants.
- `fromWireForm_shouldResolveAllSevenConstants_whenLookedUpByWireForm`
- `fromWireForm_shouldReturnEmpty_whenWireFormUnknown`

### `SourceTypeTest.java`

- `values_shouldContainExactlyFourSourceTypes_whenInspected`
- `wireForm_shouldReturnRfcCanonicalString_forEachConstant`
- `fromWireForm_shouldResolveAllFourConstants_whenLookedUpByWireForm`
- `fromWireForm_shouldReturnEmpty_whenWireFormUnknown`

### `ProductNameTest.java`

- `values_shouldContainExactlySixProducts_whenInspected`
- `wireForm_shouldReturnRfcCanonicalString_forEachConstant` — explicitly asserts `SURFACE_COMMAND.wireForm() == "Surface Command"` (the two-word form), guarding against the easy-to-make `"SurfaceCommand"` typo.
- `fromWireForm_shouldResolveAllSixConstants_whenLookedUpByWireForm`
- `fromWireForm_shouldReturnEmpty_whenWireFormUnknown`

### `BundleSchemaTest.java` — schema-validation contract tests

The load-bearing test class. Loads `/vendor-mapping/schema/v1.json` from the classpath, builds a Draft-2020-12 `JsonSchema` via `com.networknt.schema.JsonSchemaFactory`, and runs every fixture through it.

A small helper at the top of the class loads a fixture path → `JsonNode` and runs the schema, returning the validation messages set so individual tests can assert on either the empty set (valid bundle) or specific paths/codes (invalid bundle).

**Positive cases (6):**

- `validate_shouldAccept_whenBundleIsMinimalValid` — `valid-minimal.json`: one vendor → one service → one data source.
- `validate_shouldAccept_whenServicesArrayIsEmpty` — `valid-empty-services.json`: vendor with `services: []`. Per RFC §Optional fields.
- `validate_shouldAccept_whenDataSourcesArrayIsEmpty` — `valid-empty-data-sources.json`: service with `data_sources: []`.
- `validate_shouldAccept_whenServicesKeyOmitted` — `valid-no-services-key.json`: vendor object with no `services` property at all.
- `validate_shouldAccept_whenSlugIsLiteralUnknown` — `valid-unknown-slug.json`: a vendor with `id: "unknown"`. **Locks the schema/CI boundary** — RFC's reservation rule is T11's, not here.
- `validate_shouldAccept_whenMappingVersionHasPreReleaseSuffix` — `valid-mapping-version-prerelease.json`: `metadata.mapping_version: "v1.0.0-rc1"`.

**Negative cases (13):** each fixture isolates one violation; the test asserts validation fails *and* the failure path/code matches the violated rule.

- `validate_shouldReject_whenApiVersionMissing` — `invalid-missing-api-version.json`
- `validate_shouldReject_whenApiVersionIsWrongValue` — `invalid-wrong-api-version.json` (`apiVersion: "registry.rapid7.com/v2"`)
- `validate_shouldReject_whenKindIsWrongValue` — `invalid-wrong-kind.json` (`kind: "VendorMappingV2"`)
- `validate_shouldReject_whenMappingVersionMissing` — `invalid-missing-mapping-version.json`
- `validate_shouldReject_whenMappingVersionIsNotSemver` — `invalid-mapping-version-bad-format.json` (`metadata.mapping_version: "1.42"`)
- `validate_shouldReject_whenMappingVersionHasLeadingZeroPrereleaseSegment` — `invalid-mapping-version-leading-zero-prerelease.json` (`metadata.mapping_version: "v1.0.0-01"`)
- `validate_shouldReject_whenMappingVersionHasEmptyPrereleaseSegment` — `invalid-mapping-version-empty-prerelease-segment.json` (`metadata.mapping_version: "v1.0.0-."`)
- `validate_shouldReject_whenVendorSlugFailsRegex` — `invalid-vendor-slug-uppercase.json` (`vendors[0].id: "Microsoft"`)
- `validate_shouldReject_whenServiceSlugFailsRegex` — `invalid-service-slug-uppercase.json`
- `validate_shouldReject_whenCategoryNotInEnum` — `invalid-unknown-category.json` (`category: "foo"`)
- `validate_shouldReject_whenProductNotInEnum` — `invalid-unknown-product.json` (`product: "MadeUpProduct"`)
- `validate_shouldReject_whenSourceTypeNotInEnum` — `invalid-unknown-source-type.json` (`source_type: "made_up"`)
- `validate_shouldReject_whenSourceValueContainsPipe` — `invalid-source-value-with-pipe.json` (`source_value: "foo|bar"`)
- `validate_shouldReject_whenUnknownPropertyOnVendor` — `invalid-unknown-property.json` (`vendors[0].deprecated_at: "2026-01-01"`) — proves `additionalProperties: false` is in effect.
- `validate_shouldReject_whenSourceValueIsEmpty` — `invalid-source-value-empty.json`

**Total: 6 positive + 15 negative = 21 schema-validation tests.**

### Test fixtures — `src/test/resources/vendor-mapping/`

```
src/test/resources/vendor-mapping/
├── valid-minimal.json
├── valid-empty-services.json
├── valid-empty-data-sources.json
├── valid-no-services-key.json
├── valid-unknown-slug.json
├── valid-mapping-version-prerelease.json
├── invalid-missing-api-version.json
├── invalid-wrong-api-version.json
├── invalid-wrong-kind.json
├── invalid-missing-mapping-version.json
├── invalid-mapping-version-bad-format.json
├── invalid-mapping-version-leading-zero-prerelease.json
├── invalid-mapping-version-empty-prerelease-segment.json
├── invalid-vendor-slug-uppercase.json
├── invalid-service-slug-uppercase.json
├── invalid-unknown-category.json
├── invalid-unknown-product.json
├── invalid-unknown-source-type.json
├── invalid-source-value-with-pipe.json
├── invalid-unknown-property.json
└── invalid-source-value-empty.json
```

Each fixture is a minimal document — just enough structure to isolate the one assertion under test. The fixtures live under `vendor-mapping/` (not under `fixtures/sample/` like the existing adapter contract test) so the schema's classpath path and the fixture root are visibly grouped — anyone reading the test resource tree sees one folder per concern.

### Test-scope dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.5.4</version>
    <scope>test</scope>
</dependency>
```

Version `1.5.4` is the latest in the 1.5.x line, supports Draft 2020-12, and is JDK 25 compatible. The dependency is **test scope only** — production code doesn't import `com.networknt.*`. Plan 02 will likely promote this to compile scope when the parser actually runs the schema at boot; that's their decision.

### Build gate

`./mvnw verify` stays green:

- JUnit (the new tests above)
- ArchUnit (existing `LayerDependencyRules` continues to pass — `mapping` is a permitted edge from `aggregator`; nothing depends on `mapping` from `controller`/`coordinator`/`adapter`)
- PMD (curated ruleset — `AvoidDuplicateLiterals`, `CommentContent`, `UnusedFormalParameter`, etc. — applies to both main and test sources)

## Non-goals

- **No YAML parser.** Plan 02 picks up Jackson `YAMLFactory`.
- **No `VendorMappingSnapshot` *implementation*.** Plan 02 builds the Map-backed in-memory snapshot.
- **No MVP seed bundle.** Plan 02 ships the seed YAML.
- **No S3 fetch, disk cache, or readiness probe.** Plan 03.
- **No WARN logging on unknown lookups.** Plan 03 (the snapshot impl. logs at runtime; the contract just returns the synthetic value).
- **No CI uniqueness, immutability, or deprecation enforcement.** T11's bundle CI suite.
- **No aggregator, coordinator, controller, or service code.**
- **No `application.yaml` changes.** No `MAPPING_BUNDLE_VERSION`, no S3 config — Plan 03.
- **No Spring annotations on contract types.** No `@Component`, no `@Service`, no `@ConfigurationProperties`. The interface and value types are framework-agnostic.
- **No JSON serialization annotations.** No `@JsonProperty` etc. — wire-form mapping for parsing YAML is Plan 02's, and surfacing on the read API is T09's.
- **No new ArchUnit rules.** The existing `aggregator → mapping` permission is the right boundary; tighter rules would be over-engineering.
- **No KB doc updates.** KB 21.02/21.03 already describe the contract in plain language; the schema and interface are the implementation of that language.

## Acceptance signals

| Work-plan acceptance signal | How this design satisfies it |
|---|---|
| JSON Schema validates a well-formed bundle (envelope + tree) | `BundleSchemaTest.validate_shouldAccept_whenBundleIsMinimalValid` and the four other positive cases. |
| Rejects documents missing required fields | Three negative cases on `apiVersion`, `mapping_version`, plus the `additionalProperties: false` boundary. |
| Rejects invalid slug patterns | `validate_shouldReject_whenVendorSlugFailsRegex` + `_whenServiceSlugFailsRegex`. |
| Rejects `\|` in `source_value` | `validate_shouldReject_whenSourceValueContainsPipe`. |
| Snapshot interface compilable, sits in correct package | `VendorMappingSnapshot.java` in `com.rapid7.integrationregistry.mapping`. ArchUnit's `aggregatorLayer_shouldNotDependOnNonMappingLayers` already permits the inbound edge from `aggregator` (the only consumer); compilation under `./mvnw verify` is the proof. |
| Lookup result shape carries all five resolved fields | `VendorResolution` has `vendorServiceId`, `vendorServiceName`, `vendorCategory`, `vendorId`, `vendorName`. Five `requireNonNull` guards. |
| Unknown-source contract expressible through the result | `VendorResolution.unknown()` factory returns the synthetic triplet exactly as RFC-001 §Bundle lifecycle specifies. Tested. |

Plus the locked-in design's own signals:

- The three enums each carry their RFC-canonical wire form via `wireForm()` and round-trip via `fromWireForm()`. Tested.
- The schema declares `$schema: https://json-schema.org/draft/2020-12/schema` and `$id: https://registry.rapid7.com/schemas/vendor-mapping/v1.json`.
- `additionalProperties: false` at every object level. Tested via `invalid-unknown-property.json`.
- The reserved `unknown` slug **passes** schema validation (T11's CI is the enforcement boundary). Tested via `valid-unknown-slug.json`.
- `./mvnw verify` is green: JUnit + ArchUnit + PMD.

## References

- `engagements/unified-integrations-view/decisions/rfc/RFC-001-integration-registry.md`
  - §Vendor mapping → Bundle structure (lines 1073–1146) — YAML envelope, three-level tree, slug regex, optional empty arrays
  - §Vendor mapping → Bundle lifecycle (lines 1148–1196) — lookup signature, unknown-source synthetic triplet contract, `unknown` slug reservation
  - §Bundle validation (lines 1198–1219) — the schema-enforceable subset (envelope, slug regex, enums, source_value pipe ban)
  - §Canonical `productName()` values (lines 786–807) — `InsightIDR`, `InsightConnect`, `Surface Command`, `InsightVM`, `InsightCloudSec`, `InsightAppSec`
  - §`source_type` enum (lines 810–828) — `plugin_name`, `product_type`, `product_name`, `integration_id`
- `engagements/unified-integrations-view/docs/20-29-technical-design/21-architecture/21.02-reference-integration-schema.md` — vendor categories enum (7), normalized record shape
- `engagements/unified-integrations-view/docs/20-29-technical-design/21-architecture/21.03-reference-entity-model.md` — entity model resolution path, cardinalities
- `engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/scope.md`
- `repos/platform/integration-registry/TESTING.md`
- `repos/platform/integration-registry/src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
- Sibling work plans: `01-vendor-mapping-contract.md` (this), `02-bundle-parser-snapshot-seed.md`, `03-s3-loader-readiness-gate.md`
- Recently merged spec for pattern reference: `docs/superpowers/specs/2026-05-26-01-adapter-contract-design.md` (track-05/wp-01)
