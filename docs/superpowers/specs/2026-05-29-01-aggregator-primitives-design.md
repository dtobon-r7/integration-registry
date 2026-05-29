# Design — Aggregator primitives and projection records

**Work plan**: [`engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/work-plans/01-aggregator-primitives.md`](../../../../../engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/work-plans/01-aggregator-primitives.md)
**Track**: 08 — Vendor aggregator and health rollup
**Branch**: `worktree-track-08-wp-01` (worktree at `.claude/worktrees/track-08-wp-01`)
**Date**: 2026-05-29

---

## Outcome

Land the **typed surface and pure logic** that the rest of Track 08 (and Track 09's read API) compose against:

1. A pure status-precedence function (`HealthRollup.worstOf`) over the existing 5-value `IntegrationStatus` enum, implementing the RFC ordering `error > missing_data > warning > disabled > healthy`.
2. A pure `data_source_id` mint helper (`DataSourceIdMinter.mint`) implementing the RFC formula `lower(productName).replace(' ', '-') + '|' + sourceType + '|' + sourceValue`.
3. Seven projection record types — `IntegrationTypeCount`, `VendorCard`, `VendorServiceCard`, `VendorScopedView`, `VendorServiceDetail`, `DataSourceDetail`, `IntegrationDetail` — carrying every RFC §Entity-fields field at the correct types and nullability.

No grouping, no rollup across collections, no snapshot use, no projection logic — Plan 02 owns those. This plan exists as the **stagger point** that lets Track 09 begin shaping its read-API serialization against stable record types without waiting on the aggregator implementation.

After this PR ships, Plan 02 has every primitive it needs to compose the actual `VendorAggregator`, and Track 09 has stable record shapes to mount serializer code against.

## Architecture

All deliverables land under the existing `com.rapid7.integrationregistry.aggregator` package, which currently contains only `package-info.java`. No new sub-packages.

```
src/main/java/com/rapid7/integrationregistry/aggregator/
├── package-info.java                       (existing — Javadoc updated)
├── HealthRollup.java                       (final class, private ctor, public static worstOf)
├── DataSourceIdMinter.java                 (final class, private ctor, public static mint)
├── IntegrationTypeCount.java               (record)
├── VendorCard.java                         (record — /vendors projection)
├── VendorServiceCard.java                  (record — /vendor-services projection)
├── VendorScopedView.java                   (record — /vendors/{id} projection)
├── VendorServiceDetail.java                (record — /vendor-services/{id} projection)
├── DataSourceDetail.java                   (record — nested under VendorServiceDetail)
└── IntegrationDetail.java                  (record — nested under DataSourceDetail)

src/test/java/com/rapid7/integrationregistry/aggregator/
├── HealthRollupTest.java
├── DataSourceIdMinterTest.java
└── ProjectionRecordsTest.java
```

### Layer-boundary change (load-bearing)

The work plan reuses `IntegrationStatus` from `..adapter..` per "do not redefine" guidance, but the existing ArchUnit rule `aggregatorLayer_shouldNotDependOnNonMappingLayers` (in `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`) forbids `..aggregator..` → `..adapter..`. The fix:

```java
// before
static final ArchRule aggregatorLayer_shouldNotDependOnNonMappingLayers =
    noClasses().that().resideInAPackage("..aggregator..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..controller..", "..service..", "..coordinator..", "..adapter..");

// after — rename for clarity, drop ..adapter.. from deny list
static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
    noClasses().that().resideInAPackage("..aggregator..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..controller..", "..service..", "..coordinator..");
```

Justification: the aggregator legitimately consumes adapter-shaped output (`NormalizedIntegration` and its `IntegrationStatus`). The original rule was overly strict. The remaining deny list still enforces the real invariants — aggregator must not call back upstream into the request lifecycle (controller/service/coordinator).

The corresponding test reference in `LayerDependencyRulesTest` is updated to point at the renamed constant.

### Dependencies — what each new type may import

| New type | May depend on |
|---|---|
| `HealthRollup` | `com.rapid7.integrationregistry.adapter.IntegrationStatus`, `java.util.Objects` |
| `DataSourceIdMinter` | `com.rapid7.integrationregistry.mapping.SourceType`, `java.util.Locale`, `java.util.Objects` |
| `IntegrationTypeCount`, `VendorCard` | `java.util.Objects` |
| `VendorServiceCard` | `IntegrationStatus`, `VendorCategory`, `IntegrationTypeCount`, `java.time.Instant`, `java.util.List`, `java.util.Objects` |
| `VendorScopedView` | `VendorServiceCard`, `IntegrationStatus`, `Instant`, `List`, `Objects` |
| `VendorServiceDetail` | `IntegrationStatus`, `VendorCategory`, `IntegrationTypeCount`, `DataSourceDetail`, `Instant`, `List`, `Objects` |
| `DataSourceDetail` | `IntegrationStatus`, `IntegrationDetail`, `List`, `Objects` |
| `IntegrationDetail` | `IntegrationStatus`, `Instant`, `Objects` |

No Spring annotations on any of these types. Plan 01's surface is plain JVM records and pure static methods.

## `HealthRollup` — precedence function

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import java.util.Objects;

public final class HealthRollup {

    private HealthRollup() {}

    /**
     * Worst-state-wins reduction over the RFC-001 status precedence:
     * {@code error > missing_data > warning > disabled > healthy}.
     *
     * <p>Call sites that need to reduce a stream of statuses can use
     * {@code stream.reduce(HealthRollup::worstOf)} directly.
     */
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

The exhaustive `switch` over the enum is the safety mechanism: adding a sixth state to `IntegrationStatus` breaks the build at this exact line, forcing the new state to be ranked explicitly. No varargs/Iterable overload — Plan 02 callers reduce streams with `Stream.reduce(HealthRollup::worstOf)` and handle the empty-stream case where it makes sense.

**Why `>=` rather than `>`**: when both ranks are equal (e.g. `HEALTHY + HEALTHY`), the function is total and returns one of the (equal) values. The choice between `a` and `b` for ties does not affect correctness because the values compare equal; using `>=` keeps the implementation a single comparison.

## `DataSourceIdMinter`

```java
package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;
import java.util.Locale;
import java.util.Objects;

public final class DataSourceIdMinter {

    private static final char DELIMITER = '|';

    private DataSourceIdMinter() {}

    /**
     * Mint the canonical {@code data_source_id} for a resolved triplet per
     * RFC-001 §Data Model → {@code data_source_id} construction:
     *
     * <pre>data_source_id = lower(productName).replace(' ', '-')
     *                  + '|' + sourceType.wireForm()
     *                  + '|' + sourceValue</pre>
     *
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

**Why `SourceType` and not `String` for the type discriminator**: `SourceType` is an existing closed enum at `..mapping..` whose `wireForm()` returns the canonical lowercase string the RFC formula encodes. The aggregator already depends on `..mapping..`. Keying on the enum prevents the `plugin_name` vs `pluginName` footgun and makes call sites self-documenting.

**Why `Locale.ROOT` and not the default locale**: `String.toLowerCase()` without a locale is locale-sensitive; Turkish and a handful of others alter `I` and `İ` outside the ASCII range. The canonical wire form must be deterministic regardless of the host's locale.

**Why minter-side validation of `|` in `sourceValue`**: RFC §`data_source_id` construction defers this to bundle CI. JVM-side defensive validation costs nothing and surfaces the constraint at the JVM boundary rather than as a downstream parsing surprise.

## Projection records

All seven records use the existing repo convention for null validation and defensive copying:

```java
// Pattern (illustrated on a generic record)
public record FooBar(String fieldA, List<Bar> fieldB) {
    static final String FIELD_A = "fieldA";
    static final String FIELD_B = "fieldB";

    public FooBar {
        Objects.requireNonNull(fieldA, FIELD_A);
        Objects.requireNonNull(fieldB, FIELD_B);
        fieldB = List.copyOf(fieldB);  // defensive immutable copy
    }
}
```

Field-name `static final String` constants are package-private and pair 1:1 with NPE messages, matching `NormalizedIntegration`, `VendorResolution`, `FetchResult`, `SourceIdentifier`. The `static final` pattern lets test code reference the exact NPE message via `FooBar.FIELD_A` (already used in `NormalizedIntegrationTest`).

### `IntegrationTypeCount`

```java
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
                FIELD_ERROR_COUNT + " (" + errorCount + ") must be <= " + FIELD_TOTAL + " (" + total + ")");
        }
    }
}
```

The `errorCount <= total` invariant is the kind of bug Plan 02 will hit if it computes counts wrong. Putting the invariant on the record itself catches mistakes at construction rather than in serialization.

### `VendorCard` — `/vendors` projection

Per RFC: intentionally narrow. No `aggregate_health`, no `vendor_category` (vendors span categories).

```java
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

### `VendorServiceCard` — `/vendor-services` projection

Full computed set per RFC Vendor Service entity, plus `vendorId` and `vendorName` embedded so the UI can render the vendor filter chip without a separate lookup.

```java
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
    Instant lastUpdated   // nullable
) {
    // FIELD_* constants + compact ctor: NPE all non-null fields,
    //   List.copyOf the two collections, integrationsConnected >= 0.
    //   lastUpdated is nullable per RFC: null when no instance has yet recorded
    //   a successful timestamp.
}
```

### `VendorScopedView` — `/vendors/{vendor_id}` projection

```java
public record VendorScopedView(
    String vendorId,
    String vendorName,
    int vendorServicesCount,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated,            // nullable
    List<VendorServiceCard> vendorServices
) {
    // FIELD_* constants + compact ctor: NPE all non-null fields, copy collection,
    //   vendorServicesCount >= 0, lastUpdated nullable per RFC.
}
```

The nested type `vendorServices` is `VendorServiceCard` reused. The "vendor_id / vendor_name omitted in nested form" point in RFC §Projections is a wire-format/serialization concern owned by Track 09 — JVM-side, the same record carries the data.

### `VendorServiceDetail` — `/vendor-services/{vendor_service_id}` projection

```java
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
    Instant lastUpdated,            // nullable
    List<DataSourceDetail> dataSources
) { /* same validation pattern */ }
```

### `DataSourceDetail`

```java
public record DataSourceDetail(
    String dataSourceId,
    String displayName,
    String integrationType,
    String productName,
    IntegrationStatus status,
    int integrationsCount,
    List<IntegrationDetail> integrations
) {
    // FIELD_* constants + compact ctor: NPE all non-null fields, copy collection,
    //   integrationsCount >= 0.
}
```

`integrationsCount` mirrors RFC §Data Source's "Number of configured instances in the requesting org. Equal to `integrations.length`" — surfacing it as an explicit field matches the wire shape RFC §Entity fields commits to. The compact constructor enforces `integrationsCount == integrations.size()` so the two cannot diverge.

### `IntegrationDetail`

```java
public record IntegrationDetail(
    String integrationId,
    String dataSourceId,
    String integrationLabel,        // nullable per RFC
    IntegrationStatus status,
    Instant lastSuccessTimestamp,   // nullable per RFC
    String configurationUrl
) {
    // FIELD_* constants + compact ctor: NPE non-nullable fields,
    //   integrationLabel and lastSuccessTimestamp accept null.
}
```

## Tests

Three test files mirror the production layout. All tests are pure JUnit 5 + AssertJ; no Spring context.

### `HealthRollupTest`

- Parametrized over the full 5×5 status matrix (25 ordered pairs); each row asserts the worst-state-wins outcome. The expected matrix is derived once from the RFC ordering and inlined as test data.
- Symmetry property: `assertThat(worstOf(a,b)).isEqualTo(worstOf(b,a))` for every pair.
- Reflexive cases: `worstOf(s, s) == s` for every value.
- NPE on null inputs, `withMessage("a")` / `withMessage("b")`.

### `DataSourceIdMinterTest`

- Three RFC vectors as named test cases, byte-for-byte assertions:
  - `mint("InsightIDR", PRODUCT_TYPE, "microsoft-defender-endpoint")` → `"insightidr|product_type|microsoft-defender-endpoint"`
  - `mint("InsightConnect", PLUGIN_NAME, "microsoft-defender")` → `"insightconnect|plugin_name|microsoft-defender"`
  - `mint("Surface Command", INTEGRATION_ID, "com.rapid7.microsoft-defender-for-endpoint")` → `"surface-command|integration_id|com.rapid7.microsoft-defender-for-endpoint"`
- Locale-stability test: temporarily set `Locale.setDefault(new Locale("tr","TR"))` for the duration of one assertion (and restore in `finally`) to verify that `Locale.ROOT` keeps the slug deterministic on Turkish locales.
- Rejection tests: NPE on each null input (with `withMessage` matching argument name), `IllegalArgumentException` on empty `productName`, empty `sourceValue`, `|` in `sourceValue`.

### `ProjectionRecordsTest`

One nested test class per record, each covering:

- Full-construction happy path — all fields populated, getters return same values.
- NPE per non-nullable field, with `assertThatNullPointerException().withMessage(FOO.FIELD_X)`.
- Accept-null tests for fields RFC marks nullable.
- Defensive-copy verification for collection fields: build the record with a mutable list, mutate the input list, verify the record's accessor returns an unchanged value (typically by mutating and asserting `.size()` or attempting to mutate the returned list and asserting `UnsupportedOperationException` from the `List.copyOf` view).
- For `IntegrationTypeCount`: `IllegalArgumentException` cases for negative `total`, negative `errorCount`, `errorCount > total`.

### Test naming convention

Mirror `NormalizedIntegrationTest`'s pattern — `methodName_shouldDoX_whenY`.

## ArchUnit rule update

A change in `LayerDependencyRules.java`:

```java
// renamed and deny-list narrowed
static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
    noClasses().that().resideInAPackage("..aggregator..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..controller..", "..service..", "..coordinator..");
```

The corresponding `@ArchTest` reference in `LayerDependencyRulesTest.java` is updated to the new constant name. No test for the *removed* `..adapter..` rule is added — the new rule's existence is sufficient evidence of the boundary.

## `package-info.java` update

The existing one-line Javadoc ("Vendor-service grouping and worst-state-wins health rollup.") is broadened to reflect that this package now also holds the typed surface — the projection records and pure helpers — that Plan 02's aggregator and Track 09's read API will compose against. Two sentences max.

## Quality gates

- `./mvnw verify` stays green: JUnit, ArchUnit, PMD, build.
- PMD suppressions are local and justified — `@SuppressWarnings("PMD.ExcessiveParameterList")` on `VendorServiceCard` and `VendorServiceDetail` only, with a one-line comment explaining the RFC-driven field count.
- ArchUnit rule renamed and narrowed; corresponding test reference updated. No other ArchUnit changes.
- No new Maven dependencies. No new Spring beans. No Spring or AWS imports anywhere in this plan.

## Non-goals (re-stated for the implementer)

- No grouping or rollup *across* collections — only the binary `worstOf` primitive. Stream reductions live at the call site (Plan 02).
- No snapshot use, no triplet resolution. The records accept already-resolved canonical fields.
- No `List<NormalizedIntegration>` → `List<VendorServiceCard>` projection logic.
- No unknown-source synthesis. The synthetic-row collapse is an aggregator behavior; this plan only owns the record types unknown rows happen to populate.
- No KB doc updates. RFC and KB are aligned with the four-layer model.
- No serialization concerns. JSON shape, field-name casing, nullable-omission rules, and OpenAPI alignment are Track 09's responsibility.

## Open questions

None. The package-boundary decision (relax ArchUnit `..aggregator..` → `..adapter..`) is settled. The PMD suppression decision (target the canonical record constructor with `@SuppressWarnings` on the two records exceeding 10 fields) is settled.

## Acceptance signals (verbatim from the work plan, with implementation notes)

- Precedence function returns the worst state for every unordered pair across the 5 RFC values, including reflexive pairs (`error+healthy → error`, `warning+missing_data → missing_data`, `disabled+disabled → disabled`), with no fallthrough. → `HealthRollupTest`'s parametrized 5×5 matrix proves this directly.
- Mint helper produces the three canonical RFC outputs byte-for-byte. → `DataSourceIdMinterTest`'s three named vector tests prove this.
- Projection records' field sets, types, and nullability match RFC §Entity fields. → Compact constructor validation + `ProjectionRecordsTest` constructibility tests prove this; the nullability table in this design is the contract.
- `IntegrationTypeCount` exposes only `integrationType`, `total`, and `errorCount`. → Three-field record locked in.
- ArchUnit rules from Track 03 still pass. → Renamed and narrowed `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping` is the only ArchUnit edit; all other rules unchanged.
- `./mvnw verify` stays green. → Final gate of the plan.
