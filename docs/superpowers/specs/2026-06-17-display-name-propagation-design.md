# Design — Curated `display_name` Propagation (data-source level)

**Date:** 2026-06-17
**Status:** Approved (brainstorm) — ready for implementation plan
**Scope ruling:** The previously deferred `displayName` gap is **closed** as of this work. No separate ADR requested; this spec records the decision.

## Problem

The expanded data-source rows in the Integrations grid render the raw product
source slug (e.g. `rapid7_insightconnect_microsoft_defender`) instead of the
curated, human-friendly label (e.g. `Microsoft Defender`).

The curated label already exists in the vendor-mapping bundle as the
`display_name` field on each data source, and the bundle JSON Schema
(`vendor-mapping/schema/v1.json:68,73`) makes it `required` / `minLength: 1`.
But the value never reaches the wire:

1. **Parse-time drop** — `BundleParser.buildSnapshot()`
   (`mapping/BundleParser.java:145-160`) reads `product`, `source_type`,
   `source_value` per data source but never reads `display_name`.
2. **Structural gap** — `VendorResolution` (`mapping/VendorResolution.java`)
   has only 5 fields (vendor-service identity) and no place for a
   data-source-level label.
3. **Deliberate substitution** — `VendorAggregator.resolveOne()`
   (`aggregator/VendorAggregator.java:228-229`) sets the data-source
   `displayName` to the raw `sourceValue` on purpose, citing a
   "deferred-scope ruling".

From `ResolvedInstance` onward the value flows correctly to the wire
(`DataSourceDetail.displayName` → `DataSourceDto`); the only defect is that it
carries the slug instead of the curated label.

## Cardinality (the decisive constraint)

`display_name` is **data-source-level** data. The snapshot is keyed per triplet
`(product, source_type, source_value)` = **per data source**. But
`VendorResolution` represents **vendor-service identity**, and *multiple data
sources legitimately share one vendor service*.

This is in the MVP seed: the `microsoft-defender` vendor service has two data
sources with **different** display names —

- IDR `microsoft-defender-endpoint` → "Microsoft Defender for Endpoint"
- ICON `rapid7_insightconnect_microsoft_defender` → "Microsoft Defender"

Both resolve to the **same** `VendorResolution` (vendor service
`microsoft-defender`). This is the cross-product merge working as designed
(KB `21.03` Entity Model; work-plan 02 acceptance signal #4).

**Therefore `display_name` must stay at data-source cardinality and must NOT be
added to `VendorResolution`.** Adding it would (a) conflate data-source display
into vendor-service identity, and (b) break
`VendorAggregator.warnIfVendorServiceIdentityInconsistent` (`:291`), which uses
whole-record `VendorResolution.equals()` to detect bundle-integrity problems —
the two Defender data sources would become non-equal and fire a false integrity
WARN on every Defender request.

## Decisions (from brainstorm)

1. **Scope:** in-scope now; deferral closed; no ADR.
2. **Maintain changes at the data-source level** (the cardinality constraint above).
3. **Mapped triplets:** curated `display_name` is **required**, fail-fast at the
   bundle. No fallback to `sourceValue`.
4. **Unmapped triplets** (triplet not in bundle → "unknown"): keep RFC-001's
   **never-drop** invariant, render a **fixed "Unknown" label** — never the raw
   slug, never a `sourceValue` fallback.

## Approach (chosen: A — richer lookup return type)

Introduce a new record in the `mapping` package that pairs the two cardinalities
without merging them. `VendorMappingSnapshot.lookup(...)` returns this instead of
a bare `VendorResolution`.

```java
public record DataSourceResolution(VendorResolution identity, String displayName) {
    // unknown() singleton: identity = VendorResolution.unknown(), displayName = "Unknown"
}
```

- `identity()` — the **unchanged** 5-field `VendorResolution`. `VendorResolution`
  itself is not modified, so the `equals()`-based integrity check keeps working
  and all `new VendorResolution(...)` call sites are untouched.
- `displayName()` — the curated, data-source-level label.

Rejected alternatives:
- **B (second snapshot method `displayName(triplet)`):** clean on cardinality
  but duplicates the unknown-decision across two methods and does two map reads
  per triplet.
- **C (6th field on `VendorResolution`):** violates decision #2; breaks the
  identity-equality integrity check; touches all 23 `new VendorResolution(...)`
  sites.

## Component changes (data-flow order)

1. **`DataSourceResolution` (new, `mapping`)** — record with non-null
   compact-constructor guards (mirrors `VendorResolution` style).
   `unknown()` singleton = `new DataSourceResolution(VendorResolution.unknown(),
   "Unknown")`; `UNKNOWN_DISPLAY_NAME = "Unknown"` is the single home for the
   fixed unmapped label.

2. **`BundleParser.buildSnapshot()` (`:145-160`)** — read
   `ds.get("display_name").asString()` per data source (guaranteed present /
   non-blank because schema validation runs before `buildSnapshot`; no fallback,
   no defensive default). Index maps `TripletKey → DataSourceResolution`.

3. **`MapBackedVendorMappingSnapshot`** — `index` becomes
   `Map<TripletKey, DataSourceResolution>`; `lookup` returns
   `DataSourceResolution`, `getOrDefault(..., DataSourceResolution.unknown())`.

4. **`LoggingVendorMappingSnapshot` (`:41`)** — hot-path reference check becomes
   `resolution.identity() == VendorResolution.unknown()` (still a singleton
   reference check; PMD `CompareObjectsWithEquals` suppression rationale holds).

5. **`VendorMappingSnapshotHolder`** — `lookup` return-type change only.

6. **`VendorAggregator.resolveOne()` (`:208-237`)** — `lookup` returns
   `DataSourceResolution dsr`; build `ResolvedInstance` with `dsr.identity()`
   (resolution) and `dsr.displayName()` (displayName). **Delete** the
   `// displayName == sourceValue is intentional` comment and the `sourceValue`
   substitution. Unmapped branch (`:233`) uses `DataSourceResolution.unknown()`
   (displayName "Unknown"). WARN-detection: `Objects.equals(dsr.identity(),
   VendorResolution.unknown())`.

Everything downstream of `ResolvedInstance` (`buildDataSourceDetail` →
`DataSourceDetail` → `DataSourceDto`) is already correct and unchanged — it
finally receives a real value.

## Comment / doc corrections (prevent comment rot)

- `ResolvedInstance` javadoc (`:10-16`) — remove "displayName is the raw
  sourceValue … until the snapshot surfaces curated bundle display_name values";
  state it now carries the curated value ("Unknown" for unmapped).
- `mvp-seed.yaml` header (`:2-8`) — remove "the parser does not propagate them
  into VendorResolution"; state the parser now propagates `display_name` via
  `DataSourceResolution`.
- `VendorAggregator.resolveOne` inline comment (`:228`) — deleted with the
  substitution.

## Error handling & invariants

- **Fail-fast (mapped):** no try/catch, no default. A bundle missing
  `display_name` fails schema validation at load, which aborts boot via the
  existing readiness gate. We add nothing; we rely on the existing gate.
- **Never-drop (unmapped):** preserved — unmapped still produces the synthetic
  "unknown" row, now labeled "Unknown" instead of the slug.
- **Identity equality untouched:** `VendorResolution` unchanged ⇒
  bundle-integrity WARN logic and `unknown()` reference checks unaffected.
- **Thread-safety:** `DataSourceResolution` is an immutable record; snapshot
  stays immutable. No change to the documented concurrency posture.

## Testing (TDD; per binding TESTING.md)

Conventions are binding: `method_shouldDoX_whenY`, Arrange-Act-Assert with
comments, unit-first pyramid. Write/adjust failing assertion first, then
implement.

- **New `DataSourceResolutionTest`** — null guards; `unknown()` identity +
  "Unknown" label.
- **`VendorResolutionTest`** — unchanged (record untouched).
- **`BundleParserTest`** — assert `lookup(...).displayName()` equals curated
  bundle value. Verify the schema/parser test suite already rejects a bundle
  missing `display_name`; add a negative case only if not already covered.
- **`MapBackedVendorMappingSnapshotTest`, `MapBackedSnapshotBuilder`,
  `StubVendorMappingSnapshot`** — return-type migration to
  `DataSourceResolution`; `MapBackedSnapshotBuilder.map(...)` gains a display-name
  argument.
- **`VendorAggregatorTest`** (key behavioral assertions): mapped Defender ICON
  instance → data-source `displayName` "Microsoft Defender" (not slug); the
  two-data-source merge carries two distinct display names under one vendor
  service; unmapped → "Unknown".
- **`MvpSeedBundleTest`, `VendorMappingBootIntegrationTest`,
  `ReadPathTestSupport`/integration** — curated names surface end-to-end on the
  wire DTO.
- **`LoggingVendorMappingSnapshotTest`, `VendorMappingSnapshotHolderTest`** —
  return-type migration; WARN-trigger assertion via `.identity()`.

**Green-bar gate:** `./mvnw spotless:apply` then `./mvnw verify` (Docker daemon
running for the Valkey/Testcontainers cache tests, per ADR-006).

## Out of scope

- No change to `VendorResolution`'s fields or equality.
- No change to the vendor-service grid-row name path (`vendor_service_name` /
  `service.name` already propagates correctly).
- No change to RFC-001's never-drop invariant or the unknown-collapse rule.
- No paged-fetch, adapter, cache, or HTTP-contract changes.

## Blast radius summary

- **Main:** `DataSourceResolution` (new), `BundleParser`,
  `MapBackedVendorMappingSnapshot`, `LoggingVendorMappingSnapshot`,
  `VendorMappingSnapshotHolder`, `VendorAggregator.resolveOne`, `ResolvedInstance`
  javadoc, `mvp-seed.yaml` header comment.
- **Tests:** new `DataSourceResolutionTest`; migrate/extend the snapshot,
  parser, aggregator, boot-integration, and read-path tests listed above.
- **Unchanged but verified-correct:** `VendorResolution`, `DataSourceDetail`,
  `DataSourceDto`, `VendorService` detail assembly, downstream projections.
