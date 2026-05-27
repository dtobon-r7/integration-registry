# Design — Bundle parser, snapshot implementation, and MVP seed

**Work plan**: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/02-bundle-parser-snapshot-seed.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/02-bundle-parser-snapshot-seed.md)
**Track**: 04 — Vendor mapping bundle and snapshot
**Branch**: `worktree-track-04-plan-02` (worktree at `repos/platform/integration-registry/.claude/worktrees/track-04-plan-02`)
**Date**: 2026-05-27

---

## Outcome

Land the **stateless data layer** of vendor mapping: a pure function from "bundle YAML bytes" → "queryable immutable `VendorMappingSnapshot`." Plan 01 shipped the contract layer (interface, record, enums, JSON Schema). This plan ships the data layer that produces the snapshot.

Three production types under `com.rapid7.integrationregistry.mapping` plus one MVP seed YAML resource:

1. **`BundleParser`** — public final class, instantiable via `new BundleParser()`; single method `parse(InputStream) throws BundleParseException` returning `VendorMappingSnapshot`.
2. **`BundleParseException`** — public checked exception (extends `Exception`), carrying YAML-parse causes or schema-validation messages. Plan 03 will catch this to drive readiness-probe logic.
3. **`MapBackedVendorMappingSnapshot`** — package-private final class implementing `VendorMappingSnapshot`. Defensively-copied `Map<TripletKey, VendorResolution>` plus a `String mappingVersion`. Private nested record `TripletKey`.
4. **`src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`** — locked four-triplet MVP seed referenced by Plan 03's mocked-S3 integration tests.

After this PR ships, Plan 03 wraps the parser in Spring (`@Bean`/`@Component`) and adds the S3 fetch, disk cache, readiness gate, and WARN logging on unknowns — all of which are out of scope here.

## Architecture

The parser/snapshot pair is **deliberately framework-agnostic**. No Spring annotations, no logging, no clock, no I/O beyond reading the `InputStream` the caller hands in. The whole package is testable with vanilla JUnit + AssertJ.

The parser owns three responsibilities, in order:

1. **Parse YAML to `JsonNode`** via Jackson (`ObjectMapper(new YAMLFactory())`) — fails fast on YAML syntax errors.
2. **Schema-validate the parsed tree** against the Draft 2020-12 schema at `/vendor-mapping/schema/v1.json` (loaded once in the parser's constructor) — fails fast on structural violations.
3. **Build the immutable index** by walking `spec.vendors[].services[].data_sources[]`, converting wire-form strings to typed enums via Plan 01's `fromWireForm()` helpers, and emitting one `VendorResolution` per data source keyed by `(productName, sourceType, sourceValue)`.

The snapshot itself does no work beyond `Map.getOrDefault(...)` lookups. It cannot fail at runtime — `lookup()` is a total function returning either the indexed `VendorResolution` or `VendorResolution.unknown()`. The aggregator (T08) never has to wrap calls in try/catch.

**Boundary**: nothing in `mapping/` depends on any other internal Registry layer. Imports allowed: JDK, `com.fasterxml.jackson.*`, `com.networknt.schema.*`. Imports forbidden: `com.rapid7.integrationregistry.{controller,service,coordinator,adapter,aggregator}`. The existing `LayerDependencyRules` enforces this from the consumer side; no new ArchUnit rule is added by this PR.

## Components

All Java code under `src/main/java/com/rapid7/integrationregistry/mapping/`.

### `BundleParser.java`

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
import java.util.Set;

/**
 * Parses a vendor-mapping bundle YAML document into an immutable
 * {@link VendorMappingSnapshot}. Stateless and framework-agnostic;
 * Plan 03 wires this into Spring with the S3 fetch and readiness gate.
 */
public final class BundleParser {

    private static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";

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
     *     document fails JSON Schema validation. The exception carries either
     *     the underlying Jackson error as its cause (YAML syntax) or the
     *     {@code Set<ValidationMessage>} from the validator (schema violation).
     * @throws NullPointerException if {@code yamlStream} is null
     */
    public VendorMappingSnapshot parse(InputStream yamlStream) throws BundleParseException {
        Objects.requireNonNull(yamlStream, "yamlStream");
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
        Map<MapBackedVendorMappingSnapshot.TripletKey, VendorResolution> index = new HashMap<>();
        for (JsonNode vendor : tree.at("/spec/vendors")) {
            String vendorId = vendor.get("id").asText();
            String vendorName = vendor.get("name").asText();
            JsonNode services = vendor.get("services");
            if (services == null) continue;
            for (JsonNode service : services) {
                String serviceId = service.get("id").asText();
                String serviceName = service.get("name").asText();
                VendorCategory category = requireEnum(
                    VendorCategory.fromWireForm(service.get("category").asText()),
                    "category", service.get("category").asText());
                JsonNode dataSources = service.get("data_sources");
                if (dataSources == null) continue;
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
                    index.put(MapBackedVendorMappingSnapshot.key(product, sourceType, sourceValue), resolution);
                }
            }
        }
        return new MapBackedVendorMappingSnapshot(index, mappingVersion);
    }

    private static <E extends Enum<E>> E requireEnum(java.util.Optional<E> resolved, String fieldName, String wireForm) {
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

Two design notes:

**1. `loadSchema()` failure is an `IllegalStateException`, not `BundleParseException`.** The schema ships inside the JAR, so its absence is a packaging defect, not a runtime concern Plan 03 should handle with a readiness-probe transition. Same logic for the enum-sync backstop in `requireEnum()`: schema validation already passed, so a `fromWireForm()` miss means the schema and the Java enum disagree — a build-time invariant violation. `EnumSchemaSyncTest` (Plan 01) is the first line of defense.

**2. `loadSchema()` uses a fresh, throwaway `ObjectMapper` (not the YAML one).** The schema file is JSON, not YAML; the `YAMLFactory`-configured mapper would still parse it (JSON is valid YAML), but using the default `ObjectMapper` makes the intent obvious. The mapper is allocated once in the parser's constructor and immediately discarded.

### `BundleParseException.java`

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
 *
 * <p>Plan 03's runtime loader catches this exception and maps it to a
 * readiness-probe-down state plus a structured log entry.
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

Static factory methods rather than overloaded public constructors. Both factories ensure `validationMessages` is non-null (always at least `Set.of()`), so callers never need a null check.

### `MapBackedVendorMappingSnapshot.java`

```java
package com.rapid7.integrationregistry.mapping;

import java.util.Map;
import java.util.Objects;

/**
 * Map-backed implementation of {@link VendorMappingSnapshot}. Constructed by
 * {@link BundleParser} from a parsed and schema-validated bundle; immutable
 * for the lifetime of the object.
 *
 * <p>Package-private — only {@code BundleParser} constructs instances. Callers
 * outside this package depend on the {@link VendorMappingSnapshot} interface.
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

Three design notes:

**1. `Map.copyOf(index)` is the immutability mechanism.** It produces a deeply unmodifiable map; callers who mutate the source map after constructing the snapshot do not affect the snapshot. The snapshot's `index` field is `final`, the map itself is unmodifiable, and the keys (records) and values (records) are deeply immutable.

**2. `TripletKey` is private and nested.** It's an implementation detail of the index. The `key(...)` factory is the package-private seam that lets `BundleParser` populate the index without leaking the type.

**3. Field-name constants are `private static final` (same convention as `VendorResolution`'s package-private constants).** PMD's `AvoidDuplicateLiterals` rule fires on a literal repeated 4+ times in the same file; the three triplet field names appear in three places (record's compact constructor + `lookup`'s null guards + tests via `key(...)`), and `index` / `mappingVersion` appear in multiple places via `requireNonNull`. Using constants is the cleanest way to keep the rule quiet without per-method suppressions.

### `package-info.java` (broaden)

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

### MVP seed YAML — `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml`

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

Four data sources across two vendors, exercising the co-located cross-product merge pattern (`microsoft-defender` has data sources from two products). `mapping_version: v1.0.0` is locked for this seed; subsequent bundle changes will bump this in their PR.

## Data flow

```
InputStream (caller-provided)
  → ObjectMapper.readTree(YAMLFactory)        ⤳ JacksonException → BundleParseException.yamlSyntaxError(cause)
  → JsonNode tree
  → schema.validate(tree)                     ⤳ non-empty Set<ValidationMessage> → BundleParseException.schemaInvalid(msgs)
  → walk spec.vendors[].services[].data_sources[]
      → for each data source:
          - VendorCategory.fromWireForm(service.category) → present (schema-enforced enum)
          - ProductName.fromWireForm(ds.product) → present
          - SourceType.fromWireForm(ds.source_type) → present
          - put (key(product, sourceType, source_value),
                 VendorResolution(serviceId, serviceName, category, vendorId, vendorName))
  → new MapBackedVendorMappingSnapshot(index, metadata.mapping_version)
```

Three error-path notes:

**Schema-passed but enum-unresolvable.** Once schema validation passes, `fromWireForm()` for the three enums must succeed because the schema's `enum` constraints mirror the Java enum wire forms (proven by `EnumSchemaSyncTest` from Plan 01). The `requireEnum()` backstop throws `IllegalStateException` if this invariant is violated — a programmer error, not a runtime concern.

**Empty arrays are legal.** A vendor with `services: []`, a vendor with no `services` key, a service with `data_sources: []`, or a service with no `data_sources` key all validate (per schema's `Optional fields` rules) and contribute zero entries to the index. The seed doesn't exercise this, but `BundleParser.buildSnapshot()` handles every shape with an explicit `if (services == null) continue` and `if (dataSources == null) continue`.

**The `unknown` slug in input.** Per Plan 01's design, the schema permits `id: "unknown"` (T11's CI is the enforcement boundary for the reservation). If a bundle ever contained it, the parser would index it. The lookup-time `unknown()` synthetic-triplet semantic is unaffected — it fires on **lookup miss**, not on encountering the literal `"unknown"` string in bundle data.

## Error handling

| Failure site | Failure mode | How it surfaces |
|---|---|---|
| `parse(null)` | `NullPointerException("yamlStream")` | Programmer error; not a recoverable runtime path. |
| YAML syntax invalid | `BundleParseException.yamlSyntaxError(cause)` | Cause is the underlying `JacksonException` / `IOException`; `validationMessages()` is empty. |
| Schema validation fails | `BundleParseException.schemaInvalid(messages)` | Cause is null; `validationMessages()` carries the validator's structured `Set<ValidationMessage>`; `getMessage()` synthesizes a multi-line summary. |
| Schema-passed but enum unresolvable | `IllegalStateException` from `requireEnum()` | Build-time invariant violation (schema/enum drift); `EnumSchemaSyncTest` is the line of defense. |
| `loadSchema()` classpath miss | `IllegalStateException` from constructor | Packaging defect — schema ships in the JAR. |
| `MapBackedVendorMappingSnapshot.lookup(null, _, _)` (and other null-arg permutations) | `NullPointerException` with field-name message | Programmer error; the snapshot is contractually total over non-null inputs. |
| `MapBackedVendorMappingSnapshot` constructor receives null map / version | `NullPointerException` with field-name message | Defensive guard; `BundleParser` always passes non-null. |

`BundleParser.parse` deliberately commits to a **single checked exception** for all bundle-data failures. Plan 03's caller can write one `try { parser.parse(stream); } catch (BundleParseException ex) { /* readiness down + log */ }` instead of branching on YAML vs. schema separately. The structured `validationMessages()` accessor lets the caller emit per-violation log entries when desired.

## Testing

Three test classes under `src/test/java/com/rapid7/integrationregistry/mapping/`. JUnit 5 + AssertJ, `methodName_shouldDoX_whenY()`, AAA-structured with explicit `// Arrange / // Act / // Assert` comments. No Spring context. Reuse `BundleSchemaResources` (the classpath helper Plan 01 already shipped) where convenient.

### `BundleParserTest.java`

Loads YAML fixtures from `/vendor-mapping/bundle/<fixture>.yaml` and exercises the parser path.

- `parse_shouldReturnSnapshot_whenValidMinimalBundle` — fixture `valid-minimal.yaml` (one vendor / one service / one data source). Asserts `snapshot.mappingVersion()` and one successful `lookup()`.
- `parse_shouldThrowBundleParseException_whenYamlSyntaxIsInvalid` — fixture `invalid-yaml-syntax.yaml`. Asserts the exception, that `validationMessages()` returns empty, and that `getCause()` is a Jackson exception (i.e. `instanceof JacksonException`).
- `parse_shouldThrowBundleParseException_whenSchemaValidationFails` — fixture `invalid-schema.yaml` (a parseable bundle with `source_value: "foo|bar"`). Asserts the exception, that `validationMessages()` is non-empty, and that at least one message's instance location contains `source_value`.
- `parse_shouldThrowNullPointerException_whenStreamIsNull` — null guard.
- `parse_shouldReturnSnapshotWithUnknownLookup_whenTripletAbsent` — parses minimal bundle, looks up a triplet that isn't in it, asserts `VendorResolution.unknown()` (and `assertThat(...).isSameAs(VendorResolution.unknown())` to pin the synthetic-instance contract).

### `MapBackedVendorMappingSnapshotTest.java`

Tests the snapshot impl in isolation, constructing it directly via the package-private constructor and `key(...)` factory.

- `lookup_shouldReturnIndexedResolution_whenTripletPresent` — happy path on a 1-entry index.
- `lookup_shouldReturnUnknownResolution_whenTripletAbsent` — uses `assertThat(...).isSameAs(VendorResolution.unknown())`.
- `lookup_shouldReturnSameInstance_acrossRepeatedCalls` — calls `lookup()` twice with the same triplet; asserts `isSameAs` between the two returns.
- `lookup_shouldThrowNPE_whenProductNameIsNull` — null guard.
- `lookup_shouldThrowNPE_whenSourceTypeIsNull` — null guard.
- `lookup_shouldThrowNPE_whenSourceValueIsNull` — null guard.
- `mappingVersion_shouldReturnConstructorValue` — accessor assertion.
- `constructor_shouldThrowNPE_whenIndexIsNull` — null guard.
- `constructor_shouldThrowNPE_whenMappingVersionIsNull` — null guard.
- `constructor_shouldDefensivelyCopy_whenMutatingSourceMapAfterConstruction` — proves `Map.copyOf()` is in effect: build a `HashMap`, construct the snapshot, mutate the source `HashMap` (add an entry), assert the snapshot's lookup behavior is unchanged.

### `MvpSeedBundleTest.java`

Loads the production seed at `/vendor-mapping/bundle/mvp-seed.yaml` from the classpath via `BundleParser`, then asserts the four MVP triplets resolve plus one negative control.

- `mvpSeed_shouldHaveMappingVersion_v1_0_0`
- `mvpSeed_shouldResolveDefenderViaIDR_toMicrosoftDefender` — `(INSIGHT_IDR, PRODUCT_TYPE, "microsoft-defender-endpoint")` → asserts the full `VendorResolution` (`vendorServiceId="microsoft-defender"`, `vendorServiceName="Microsoft Defender"`, `vendorCategory=EDR`, `vendorId="microsoft"`, `vendorName="Microsoft"`)
- `mvpSeed_shouldResolveDefenderViaICON_toMicrosoftDefender` — `(INSIGHT_CONNECT, PLUGIN_NAME, "microsoft-defender")` → same vendor service, same vendor; this case proves the cross-product merge.
- `mvpSeed_shouldResolveJiraViaICON_toJira` — `(INSIGHT_CONNECT, PLUGIN_NAME, "jira")` → `(jira, Jira, ITSM, atlassian, Atlassian)`
- `mvpSeed_shouldReturnUnknown_forUnmappedTriplet` — `(INSIGHT_IDR, PLUGIN_NAME, "not-in-the-bundle")` → `VendorResolution.unknown()`.

This is the canonical proof of the work plan's acceptance signal "the MVP seed bundle proves all four triplets resolve correctly."

### Test fixtures — `src/test/resources/vendor-mapping/bundle/`

- **`valid-minimal.yaml`** — one vendor / one service / one data source. Mirrors the schema's minimal positive case but in YAML.

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

- **`invalid-yaml-syntax.yaml`** — broken indentation that Jackson's YAML parser will reject. Specifically, a `services:` key indented inconsistently relative to its sibling list items.

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

- **`invalid-schema.yaml`** — parses as YAML, fails schema validation (`source_value` contains `|`).

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

This single schema-violation fixture is the **parser's propagation test**, not a substitute for Plan 01's schema-level coverage. Plan 01's `BundleSchemaTest` already exercises 13+ negative cases at the JsonNode level (missing `apiVersion`, bad slug regex, unknown enum values, etc.). Plan 02's parser test only needs to prove that *when schema validation fails for any reason, `BundleParser.parse` raises `BundleParseException.schemaInvalid` with the `Set<ValidationMessage>` populated.* One representative violation suffices.

### Test-scope dependency surface

No new test-scope dependencies. The existing `com.networknt:json-schema-validator:1.5.4` and the transitive Jackson stack continue to flow into tests when the pom dependency scope changes from `test` to `compile` (Maven still adds `compile` artifacts to the test classpath).

### Build gate

`./mvnw verify` stays green:

- **JUnit** — three new test classes plus all existing tests.
- **ArchUnit** — `LayerDependencyRules` continues to pass; the new types are inside `mapping/`, so no inbound-edge rule fires. The new types' imports are JDK + `com.fasterxml.jackson.*` + `com.networknt.schema.*` only.
- **PMD** — curated ruleset on both main and test sources. Specific concerns:
  - `AvoidDuplicateLiterals`: addressed by `private static final FIELD_*` constants for the recurring null-guard messages on `MapBackedVendorMappingSnapshot`. The wire-form lookup loop in `BundleParser` references each enum once per data source — at most three iterations on `valid-minimal` — so duplicate-literal does not fire there.
  - `MutableStaticState`: `MapBackedVendorMappingSnapshot.FIELD_*` constants are `private static final String` (immutable). `BundleParser.SCHEMA_CLASSPATH` is the same.
  - `CommentContent`: no `TODO|FIXME|HACK|XXX` anywhere.
  - `EmptyCatchBlock`: every `catch` either rethrows as `BundleParseException` or wraps in `IllegalStateException`. None are empty.
  - `AvoidInstantiatingObjectsInLoops`: the `BundleParser.buildSnapshot()` walk allocates `VendorResolution` and `TripletKey` per data source — that's the contract, not a performance pathology, and the rule doesn't fire on object-construction-in-loop when the constructions ARE the work being done. If it does fire (rule has been tightened in this repo's ruleset), suppress at the method level with a justification comment ending in a period.

## Dependency-scope changes

`pom.xml`:

```xml
<!-- Promote from test scope to compile scope -->
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.5.4</version>
    <!-- removed: <scope>test</scope> -->
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <!-- new: explicit declaration; version managed by the Spring Boot BOM -->
</dependency>
```

Two notes:

**1. `jackson-dataformat-yaml` becomes an explicit compile-scope dependency.** It was previously transitive through `json-schema-validator` at test scope; promoting `json-schema-validator` to compile would still bring it in transitively, but declaring it explicitly is the correct expression of intent: the parser uses YAML directly.

**2. `jackson-databind` stays implicit.** It flows transitively from `jackson-dataformat-yaml` (which depends on `jackson-databind` as a hard dep). No explicit declaration, no version pin — the Spring Boot 4 BOM manages the version, so this is the conventional Spring Boot approach.

## Non-goals

- **No S3 fetch, no AWS SDK dependency.** Plan 03.
- **No disk cache, no readiness probe.** Plan 03.
- **No Spring annotations on any class** — no `@Component`, `@Service`, `@Bean`, `@ConfigurationProperties`. Plan 03 wires this into Spring.
- **No WARN logging on unknown lookups.** Plan 03 owns runtime logging; the snapshot just returns `unknown()`.
- **No JSON serialization annotations** — no `@JsonProperty`, no Jackson POJO mapping. The parser walks `JsonNode` directly because the wire-form-to-enum mapping happens via Plan 01's `fromWireForm()` helpers.
- **No `application.yaml` or Spring profile changes** — purely classes + a YAML resource + a pom dep change.
- **No CI uniqueness, immutability, or deprecation enforcement.** T11's bundle CI suite.
- **No new ArchUnit rules.** The existing `aggregator → mapping` permission and the symmetric `controller/service/coordinator/adapter` exclusions already cover this package.
- **No KB doc updates.** The behavior is fully described by RFC-001 §Vendor mapping; no new explanation is required.
- **No expansion of the schema or any of Plan 01's contract types.** This plan implements the contract; it doesn't extend it.

## Acceptance signals

| Work-plan acceptance signal | How this design satisfies it |
|---|---|
| MVP seed parses without error; snapshot resolves all four triplets | `MvpSeedBundleTest` — five tests cover all four triplets + one negative control. |
| Unmapped triplet returns the synthetic record | `BundleParserTest.parse_shouldReturnSnapshotWithUnknownLookup_whenTripletAbsent` + `MapBackedVendorMappingSnapshotTest.lookup_shouldReturnUnknownResolution_whenTripletAbsent` + `MvpSeedBundleTest.mvpSeed_shouldReturnUnknown_forUnmappedTriplet`. All three assert `isSameAs(VendorResolution.unknown())` to pin the synthetic-instance contract. |
| `\|` in `source_value` fails schema validation | `BundleParserTest.parse_shouldThrowBundleParseException_whenSchemaValidationFails` (fixture uses this exact violation). Plan 01's `BundleSchemaTest` is the schema-level proof; Plan 02's test proves the parser propagates it. |
| Missing required field fails schema validation | Covered transitively: Plan 01's `BundleSchemaTest` proves the schema rejects each missing-field case at the `JsonNode` level; Plan 02's parser test proves any schema violation surfaces as `BundleParseException`. No additional fixtures here. |
| Bad slug regex fails schema validation | Same as above. |
| Snapshot is immutable for object lifetime | `MapBackedVendorMappingSnapshotTest.lookup_shouldReturnSameInstance_acrossRepeatedCalls` + `constructor_shouldDefensivelyCopy_whenMutatingSourceMapAfterConstruction`. |

The implementation should also satisfy these spec-level signals:

- `./mvnw verify` is green: JUnit + ArchUnit + PMD.
- The `mapping/` package, as a whole, depends only on JDK, Jackson, and `com.networknt.schema`. ArchUnit's existing rules continue to pass without modification.
- `MapBackedVendorMappingSnapshot` is package-private, with no production-code references to it outside the package — Plan 03 will autowire by interface type.

## References

- Work plan: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/02-bundle-parser-snapshot-seed.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/02-bundle-parser-snapshot-seed.md)
- Track scope: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/scope.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/scope.md)
- Sibling Plan 01 (IMPLEMENTED): [`work-plans/01-vendor-mapping-contract.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/01-vendor-mapping-contract.md), spec at [`docs/superpowers/specs/2026-05-26-01-vendor-mapping-contract-design.md`](2026-05-26-01-vendor-mapping-contract-design.md)
- Sibling Plan 03 (PENDING): [`work-plans/03-s3-loader-readiness-gate.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/03-s3-loader-readiness-gate.md)
- RFC-001 §Vendor mapping → Bundle structure (lines 1073–1146)
- RFC-001 §Vendor mapping → Bundle lifecycle (lines 1148–1196) — lookup signature, unknown-source synthetic-triplet contract, immutability for process lifetime
- KB 21.03 — Entity Model Reference, §Resolution path
- TESTING.md — unit-test conventions, naming, AAA structure
- `LayerDependencyRules` — the existing ArchUnit rules that already cover the `mapping/` package boundary
