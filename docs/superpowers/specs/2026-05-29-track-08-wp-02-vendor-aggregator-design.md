# Track 08 / Work Plan 02 — VendorAggregator design

**Date**: 2026-05-29
**Status**: Implemented (PR #9)
**Work plan**: `engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/work-plans/02-vendor-aggregator.md`
**Track scope**: `engagements/unified-integrations-view/project/tracks/08-vendor-aggregator-and-health-rollup/scope.md`
**Driving RFC**: `engagements/unified-integrations-view/decisions/rfc/RFC-001-integration-registry.md`

---

## Outcome

A `VendorAggregator` Spring `@Component` in `com.rapid7.integrationregistry.aggregator` that turns `(List<NormalizedIntegration>, VendorMappingSnapshot)` into the four projection record types ready for T09's read API to serialize:

- `List<VendorServiceCard>` — full grid (`GET /vendor-services`)
- `List<VendorCard>` — vendor filter list (`GET /vendors`, narrow shape)
- `Optional<VendorScopedView>` — vendor-scoped view (`GET /vendors/{vendor_id}`)
- `Optional<VendorServiceDetail>` — vendor-service detail (`GET /vendor-services/{vendor_service_id}`)

Pure stateless transform. No DB, no network, no cache. The `VendorMappingSnapshot` is the only mapping boundary.

---

## Architecture

```
NormalizedIntegration[]                VendorMappingSnapshot
        │                                       │
        └──────────────┬────────────────────────┘
                       ▼
              ┌────────────────────┐
              │  VendorAggregator  │  @Component, stateless
              └────────────────────┘
                       │
        ┌──────┬───────┴───────┬───────┐
        ▼      ▼               ▼       ▼
  VendorService   VendorService   VendorScoped     VendorCard
  Card[]          Detail (Opt)    View (Opt)       []
  (full grid)     (one VS)        (one vendor)     (filter list)
```

`VendorAggregator` is a Spring `@Component` with a single constructor dependency on `VendorMappingSnapshot`. ArchUnit's existing `aggregatorLayer_shouldOnlyDependOnAdapterAndMapping` rule already permits this dependency set; no rule changes.

Internally, every projection method shares one resolution pass that converts `List<NormalizedIntegration>` into a `List<ResolvedInstance>` (package-private record). Resolution + `data_source_id` mint + WARN logging happen exactly once per call. Per-projection assembly is then pure grouping over `ResolvedInstance[]`.

The aggregator is fully stateless. The WARN-dedup `Set<TripletKey>` lives in a per-call resolution context; nothing is shared across requests.

---

## Public surface

```java
package com.rapid7.integrationregistry.aggregator;

@Component
public final class VendorAggregator {

    public VendorAggregator(VendorMappingSnapshot snapshot) { ... }

    public List<VendorServiceCard> toVendorServiceCards(List<NormalizedIntegration> instances);

    public List<VendorCard> toVendorCards(List<NormalizedIntegration> instances);

    public Optional<VendorScopedView> toVendorScopedView(
            String vendorId, List<NormalizedIntegration> instances);

    public Optional<VendorServiceDetail> toVendorServiceDetail(
            String vendorServiceId, List<NormalizedIntegration> instances);
}
```

`ResolvedInstance` is package-private. Callers see only the four methods above and the existing projection records.

`DataSourceIdMinter` gains one public method:

```java
public static String mint(String productName, String sourceType, String sourceValue);

// Existing enum overload now delegates:
public static String mint(String productName, SourceType sourceType, String sourceValue) {
    return mint(productName, sourceType.wireForm(), sourceValue);
}
```

The String overload applies the same validation as the enum overload (`productName` non-blank, `sourceValue` non-empty + no `|`) **plus** the same checks on the raw `sourceType` string (non-blank, no `|`). Existing callers and tests stay green because the enum overload preserves identical behavior. This overload is what the aggregator uses when `SourceType.fromWireForm` returns empty.

---

## Internal data flow

### Shared resolution pass

```
resolve(List<NormalizedIntegration>) → List<ResolvedInstance>

For each NormalizedIntegration n:
  productNameEnum = ProductName.fromWireForm(n.productName())
  sourceTypeEnum  = SourceType.fromWireForm(n.sourceIdentifier().sourceType())

  IF both present:
    resolution   = snapshot.lookup(productNameEnum, sourceTypeEnum, sourceValue)
    dataSourceId = DataSourceIdMinter.mint(n.productName(), sourceTypeEnum, sourceValue)
    IF resolution.equals(VendorResolution.unknown()):
      warnOnceForTriplet(n.productName(), n.sourceIdentifier().sourceType(), sourceValue)
      displayName = sourceValue
    ELSE:
      displayName = sourceValue       // see "displayName gap" below
  ELSE:
    resolution   = VendorResolution.unknown()
    dataSourceId = DataSourceIdMinter.mint(   // String overload
        n.productName(), n.sourceIdentifier().sourceType(), sourceValue)
    warnOnceForTriplet(...)
    displayName = sourceValue

  emit ResolvedInstance(n, dataSourceId, resolution, displayName)
```

### Per-projection grouping

After the shared resolution pass:

- **`toVendorServiceCards`** — group `ResolvedInstance[]` by `resolution.vendorServiceId()`. For each VS group: build per-`dataSourceId` sub-groups (to roll up DS-level `status`), then aggregate at VS level. Build `VendorServiceCard` from the resolution's identity fields + computed aggregates. Insertion-stable order across VS groups.

- **`toVendorCards`** — distinct `(vendorId, vendorName)` pairs from resolution; for each, count distinct `vendorServiceId` values under that vendor. Emit one `VendorCard(vendorId, vendorName, vendorServicesCount)`. No health, no category.

- **`toVendorScopedView(vendorId, ...)`** — filter `ResolvedInstance[]` to those whose `resolution.vendorId().equals(vendorId)`. If empty → `Optional.empty()`. Otherwise produce nested `VendorServiceCard[]` (same logic as `toVendorServiceCards`, scoped) + roll up `aggregateHealth` and `lastUpdated` across those VSes. The unknown vendor id `"unknown"` resolves the same way as any real id.

- **`toVendorServiceDetail(vendorServiceId, ...)`** — filter `ResolvedInstance[]` to those resolving to this VS. If empty → `Optional.empty()`. Otherwise build `DataSourceDetail[]` (one per distinct `dataSourceId`, each with its own `IntegrationDetail[]`) plus the VS header.

### Computed-field specs

| Field | Computation |
|---|---|
| Per-DS `status` | `instances.stream().map(NormalizedIntegration::status).reduce(HealthRollup::worstOf).orElseThrow()` |
| Per-VS `aggregateHealth` | `dataSources.stream().map(DataSourceDetail::status).reduce(HealthRollup::worstOf).orElseThrow()` |
| Per-Vendor `aggregateHealth` | `vsCards.stream().map(VendorServiceCard::aggregateHealth).reduce(HealthRollup::worstOf).orElseThrow()` |
| Per-VS `integrationsConnected` | sum of instance count across all DSes under the VS |
| Per-VS `integrationTypeCounts` | group instances by `integrationType` → emit `IntegrationTypeCount(type, total, errorCount)` where `errorCount = count(status == ERROR)` |
| Per-VS `productsConnected` | distinct `productName` strings, insertion-stable order |
| Per-VS `lastUpdated` | `instances.stream().map(NormalizedIntegration::lastSuccessTimestamp).filter(Objects::nonNull).max(...).orElse(null)` |
| Per-Vendor `lastUpdated` | max non-null `lastSuccessTimestamp` across all instances under the vendor (equivalent to max-of-per-VS-`lastUpdated` since the per-VS value is itself the max-non-null over its instances; null when every instance under the vendor has null `lastSuccessTimestamp`) |
| Per-Vendor `vendorServicesCount` | distinct VS ids under the vendor |
| Per-DS `integrationsCount` | `integrations.size()` (record invariant double-checks) |

The `orElseThrow()` is intentional: an aggregator that reaches the rollup step always has at least one instance under the group it is rolling up. An empty stream is a programming error, not a runtime data condition.

### WARN log format

```
"Unmapped vendor mapping triplet: productName='{}' sourceType='{}' sourceValue='{}' mappingVersion='{}'"
```

SLF4J parameterized. One event per **distinct** triplet per request, deduped via `Set<TripletKey>` on the per-call resolution context. The triplet key is `(rawProductNameString, rawSourceTypeString, sourceValue)`.

### `displayName` gap (deferred scope)

The `VendorMappingSnapshot.lookup` method returns `VendorResolution`, which carries vendor and vendor-service identity but **not** the per-data-source `display_name` from the bundle. RFC §Data Source pins `display_name` to the bundle's curated sub-product label (e.g. "Microsoft Defender for Endpoint"); T04's snapshot doesn't surface it today.

This work plan uses the raw `sourceValue` as `displayName` for **all** data sources (mapped and unmapped). This is honest, in scope, doesn't touch T04, and satisfies the work plan's "each data source preserves its own display_name" intent — at the cost of less-readable display names for known triplets until the snapshot is extended.

**Deferred follow-up (out of scope for this work plan):** extend `VendorResolution` with a `displayName` field, or add a separate `lookupDisplayName(...)` method to `VendorMappingSnapshot`. Either route requires coordination with T04 — track this as a new work plan in T08 add-mode.

---

## Error and edge handling

| Case | Behavior |
|---|---|
| `instances` is null | `NullPointerException("instances")` — adapter contract violation. |
| `instances` is empty | All four methods return empty results (`List.of()` or `Optional.empty()`) without invoking the snapshot. **No** synthetic unknown row, **no** WARN. |
| Single instance, snapshot returns unknown | One synthetic VS card with one synthetic DS; one WARN. |
| Multiple instances sharing one unmapped triplet | One synthetic DS with N instances; **one** WARN (deduped). |
| Multiple instances with different unmapped triplets | All under one synthetic VS, one DS each; WARN count = distinct-triplet count. |
| `productName` string doesn't match `ProductName` enum | Treated as unmapped → synthetic via String mint overload. WARN fires. |
| `sourceType` string doesn't match `SourceType` enum | Same. |
| `lastSuccessTimestamp` null on every instance under a VS | Per-VS `lastUpdated` is null. The record permits null. |
| Same DS has both healthy and error instances | DS `status` = ERROR (HealthRollup picks worst). |
| `integrationLabel` is null | Passes through to `IntegrationDetail`; record permits null. |
| `instances` contains nulls | `NullPointerException` from `Objects.requireNonNull` in the resolution helper. |
| `vendorId` / `vendorServiceId` argument to scoped methods is null | `NullPointerException("vendorId")` / `("vendorServiceId")`. |
| Scoped id is `"unknown"` and there are no unmapped instances | `Optional.empty()` — no synthetic to surface. |
| Adapter-supplied `productName` is empty or whitespace-only | `IllegalArgumentException` from the String mint overload's `non-blank` validation. Treated as a programming error from the adapter layer, not folded into unknown. This is the only edge where the aggregator throws on bad data instead of degrading. |
| Adapter-supplied `sourceType` string is empty or whitespace-only | Same — `IllegalArgumentException` from the String mint overload's `non-blank` check on raw sourceType. |
| Adapter-supplied `sourceValue` is empty or contains `\|` | Same — `IllegalArgumentException` from the String mint overload's existing rules. |

---

## Testing strategy

### Approach

- **TDD** throughout. Pure unit tests, no Spring context, no `@SpringBootTest`.
- AssertJ + JUnit 5 (parameterized tests, `@Nested` for scenario families).
- `MapBackedSnapshotBuilder` driving the real `MapBackedVendorMappingSnapshot`. No Mockito.
- WARN assertions via Logback `ListAppender<ILoggingEvent>`.

### Test class structure

`src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java` with `@Nested` blocks:

```
VendorAggregatorTest
├── ResolutionTest                     known triplet, unmapped triplet, unmappable strings
├── DataSourceRollupTest               worst-state across instances under one DS
├── VendorServiceRollupTest            worst-state across DSes under one VS
├── VendorRollupTest                   worst-state across VSes under one vendor (incl. the
│                                      "rollup is across services, not directly across
│                                      instances" trap case)
├── VendorServiceCardsTest             full grid; multi-product merge; integration_type_counts;
│                                      products_connected; integrations_connected; last_updated
├── VendorCardsTest                    narrow shape; vendor_services_count; no aggregate_health
├── VendorScopedViewTest               vendor header + nested VS list; Optional.empty();
│                                      vendorId="unknown" resolves
├── VendorServiceDetailTest            VS header + data_sources[] + integrations[]; Optional.empty();
│                                      vendorServiceId="unknown" resolves
├── UnknownCollapseTest                3 unmapped triplets → 1 synthetic VS, 3 DSes; WARN count = 3;
│                                      WARN log content (raw values + mappingVersion);
│                                      displayName = sourceValue
└── EdgeCasesTest                      empty input; null arguments; null lastSuccessTimestamp on all
                                       instances under a VS

DataSourceIdMinterStringOverloadTest    sibling test class — round-trips, validation, equivalence with
                                        enum overload (a regression guard on the existing minter)
```

### Test doubles

Tests drive the **real** `MapBackedVendorMappingSnapshot` via `MapBackedSnapshotBuilder` (in the
`mapping` test source package, so it can reach the package-private `key(...)` factory and
constructor). No hand-rolled fake, no Mockito — the canonical lookup code path is exercised
directly.

```java
public final class MapBackedSnapshotBuilder {
    public static MapBackedSnapshotBuilder with(String mappingVersion) { ... }

    public MapBackedSnapshotBuilder map(ProductName productName, SourceType sourceType,
                                        String sourceValue, VendorResolution resolution) { ... }
    public VendorMappingSnapshot build() { ... }  // returns a real MapBackedVendorMappingSnapshot
}
```

`NormalizedIntegrationFixtures` — small static helpers like `idrInstance(integrationId, sourceValue, status)`, `iconInstance(integrationId, sourceValue, status, lastSuccess)` — keep each test scenario readable.

### Logback `ListAppender` pattern

```java
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
```

Then assert on `appender.list` for level=WARN, count, and message-format-expanded content via `event.getFormattedMessage()`.

### Build gate

`./mvnw verify` must stay green: JUnit, ArchUnit, PMD.

---

## Acceptance signals (from work plan)

- Precedence rule produces worst state at DS level for mixed-state instance set under one DS.
- Precedence rule at VS level for mixed-state DS set under one VS.
- Precedence rule at vendor level for mixed-state VS set under one vendor (rollup is across VSes, not directly across instances).
- Multiple unmapped triplets → exactly **one** synthetic VS card with `vendor_service_id="unknown"`, `vendor_id="unknown"`, `vendor_category="other"`; multiple data sources, one per unmapped triplet, each preserving its raw triplet in `data_source_id`.
- Every unknown-triplet lookup emits WARN log including current `mapping_version` (deduped to once per distinct triplet per request).
- For a multi-instance VS: `integrations_connected` equals instance count; `integration_type_counts` carries one entry per distinct `integration_type` with correct `total` and `error_count`; `products_connected` lists distinct contributing product names; `last_updated` equals the most recent non-null `last_success_timestamp`.
- Two integrations from different products (one ICON, one IDR) resolving to the same `vendor_service_id` appear as **two distinct data sources**, different `data_source_id` values, each with its own `display_name` and instances. (Per the `displayName` gap, `display_name` for each is currently `sourceValue` — the two will differ because the two products have different sourceValues.)
- `data_source_id` for `(InsightIDR, product_type, microsoft-defender-endpoint)` equals `insightidr|product_type|microsoft-defender-endpoint`.
- All four projection methods return shapes matching RFC §Read API Contract → Projections, including narrow `/vendors` (no `aggregate_health` on the vendor card) vs vendor-scoped (aggregate_health rolled across services).
- ArchUnit, PMD, and JUnit pass; `./mvnw verify` stays green.

---

## Non-goals

- No DB access of any kind.
- No coordinator, cache, fan-out, or stale-tier behavior.
- No HTTP serialization, error envelope, or `unavailable_products[]` envelope.
- No per-instance health derivation.
- No snapshot lifecycle / boot-time fetch / readiness gate.
- No mapping curation, admin API, or runtime mutation path.
- No stale-flag handling on the response.
- No metadata envelope assembly (`cache_hit`, `as_of`, `mapping_version`).
- No `displayName` extension to `VendorResolution` or `VendorMappingSnapshot`. Documented as deferred.

---

## Resolved questions

| # | Question | Resolution |
|---|---|---|
| Q1.1 | Aggregator behavior when `productName`/`sourceType` strings don't resolve to enum members | Treat as unmapped — fold into the unknown-collapse path with WARN log carrying raw values + `mappingVersion`. |
| Q1.2 | Synthetic data-source `displayName` value | Raw `sourceValue` verbatim. |
| Q1.3 | WARN cadence on unmapped lookups | Once per distinct unmapped triplet per request (dedup with per-call `Set<TripletKey>`). |
| Q-A | How to expose String-typed mint for the unmappable case | Public overload on `DataSourceIdMinter`. Enum overload delegates. |
| Q-B | Return type for scoped projections when the id has no instances | `Optional<VendorScopedView>` / `Optional<VendorServiceDetail>`. T09 controller maps `Optional.empty()` to 404. |

---

## File frame

| File | Type | Status |
|---|---|---|
| `src/main/java/com/rapid7/integrationregistry/aggregator/VendorAggregator.java` | New `@Component` | TODO |
| `src/main/java/com/rapid7/integrationregistry/aggregator/ResolvedInstance.java` | New pkg-private record | TODO |
| `src/main/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinter.java` | Existing — adds public String overload | EDIT |
| `src/test/java/com/rapid7/integrationregistry/aggregator/VendorAggregatorTest.java` | New test class | TODO |
| `src/test/java/com/rapid7/integrationregistry/mapping/MapBackedSnapshotBuilder.java` | New test builder over the real `MapBackedVendorMappingSnapshot` (replaces the originally-planned `FakeVendorMappingSnapshot`) | TODO |
| `src/test/java/com/rapid7/integrationregistry/aggregator/NormalizedIntegrationFixtures.java` | New test helpers | TODO |
| `src/test/java/com/rapid7/integrationregistry/aggregator/DataSourceIdMinterStringOverloadTest.java` | New sibling test class for the String overload (round-trips, validation, equivalence with enum overload). Existing `DataSourceIdMinterTest.java` stays unchanged. | NEW |

No edits to existing aggregator/, mapping/, adapter/, or other layer files beyond `DataSourceIdMinter.java`.

---

## Chain control

This spec drives `superpowers:writing-plans`, which produces a TDD-shaped task plan and chains to the implementing skill (recommend `superpowers:subagent-driven-development`).

**Critical**: when the implementing skill checks off the last task and verification is clean, it must **return control to `execute-plan`**, not auto-invoke `superpowers:finishing-a-development-branch`. The `execute-plan` harness runs Phase 7 (functional review gate), Phase 8 (simplify gate), and Phase 9 (external code review) before deciding what to do with the diff.
