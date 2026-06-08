# VendorService orchestration and response assembly — Design

**Work plan**: `engagements/unified-integrations-view/project/tracks/09-read-api-and-orchestration/work-plans/02-vendor-service-orchestration.md`
**Date**: 2026-06-05
**Status**: Approved for implementation

---

## Outcome

`VendorService` (`@Service`) orchestrates the read path for all four routes:
fan-out (T07 `FanOutCoordinator`) → aggregation (T08 `VendorAggregator`) →
response assembly into the Plan-01 wire DTOs. It computes the `metadata` block,
the `unavailable_products[]` envelope, and the 404-vs-partial-unavailability
decision for the two detail routes. It holds the engagement's honesty
invariants: `as_of` is the oldest contributing fetch, `cache_hit` requires
*every* product fresh, and emptiness/404 is asserted only when the fresh and
stale tiers agree.

## Layer boundaries (RFC-001 §Spring layer boundaries; enforced by ArchUnit)

`VendorService` knows **no HTTP** and **no mapping**. The ArchUnit rules
(`serviceLayer_shouldNotDependOnWebLayer`, `serviceLayer_shouldNotDependOnMappingLayer`,
both actively tested in `LayerDependencyViolationDetectionTest`) forbid the
`..service..` package from depending on `org.springframework.http..`,
`org.springframework.web..`, `jakarta.servlet..`, or `..mapping..`. Two seams
exist precisely so the service can honor these bans (see Tensions A and the
version/category seams below).

## Constructor dependencies

`VendorService(FanOutCoordinator coordinator, VendorAggregator aggregator, Clock clock)`.

- `Clock` is a Spring bean (`Clock.systemUTC()`) provided by a new
  `ServiceConfiguration`. Injected (not `Instant.now()`) so `as_of`-when-empty
  is deterministic in tests (TESTING.md: do not mock `Instant.now()` — inject a
  `Clock`).

## Public entry points (one per route)

```java
VendorServicesResponse                listVendorServices(String orgId, OutboundAuth auth);
VendorsResponse                       listVendors(String orgId, OutboundAuth auth);
Optional<VendorServiceDetailResponse> getVendorServiceDetail(String orgId, String vendorServiceId, OutboundAuth auth);
Optional<VendorDetailResponse>        getVendorDetail(String orgId, String vendorId, OutboundAuth auth);
```

The two list routes always return a value (a 200, possibly with empty arrays).
The two detail routes return `Optional` — `Optional.empty()` is the **not-found
signal** Plan 03 maps to HTTP 404.

## Shared orchestration spine (run once per request by every method)

1. `List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);`
2. Flatten every `Served` outcome's `integrations()` into one
   `List<NormalizedIntegration>` (a `Served` with `stale==true` still
   contributes integrations to the grid).
3. Hand that flat list to the route's aggregator method.
4. Assemble the shared `metadata` + `unavailable_products[]`, then the route
   payload.

## Shared assembly rules (the honesty invariants)

### `unavailable_products[]`

One `UnavailableProductDto` entry per non-clean product, derived from outcomes:

| Outcome | Entry |
|---|---|
| `Served`, `stale == false` | **none** (clean contributor) |
| `Served`, `stale == true` | `UnavailableProductDto(productName, stale=true, reason=map(staleReason), staleSince=staleSince.get())` |
| `Unavailable` | `UnavailableProductDto(productName, stale=false, reason=map(reason()), staleSince=null)` |

`UnavailableProductDto` enforces `staleSince` non-null iff `stale==true`, which
aligns with `ProductOutcome` exactly.

### `metadata`

- `cache_hit` = `true` **iff** every outcome is `Served` with
  `cacheHitPerProduct() == true`. Any stale serve, any freshly-fetched product,
  any `Unavailable`, or an empty outcome list → `false`.
- `as_of` = oldest `fetchedAt()` across `Served` outcomes. If there are **zero**
  `Served` outcomes (all-adapter-failure), `as_of = clock.instant()`.
- `mapping_version` = `aggregator.mappingVersion()`.

### Enum / category translation

- `IntegrationStatus` (adapter) → `HealthState` (controller.dto): 1:1 by name.
  Lives **in the service** — both packages are service-legal.
- `VendorCategory` (mapping) → wire String: the service **never names
  `VendorCategory`** (ArchUnit ban). It hands the projection record to a
  translation seam on `VendorAggregator` (mapping is legal there). Mapping:
  `edr→edr`, `siem→siem`, `itsm→itsm`, `other→other`, `cloud_provider→cloud`,
  `identity→other`, `notification→other`. Total and contract-valid against the
  openapi `VendorCategory` enum (`edr, siem, itsm, uem, cloud, productivity,
  other`). MVP data (Defender edr/siem, Jira itsm) is unaffected by the
  remaps.
- `UnavailableReason` mapping: the coordinator's `reason` strings
  (`timeout | upstream_5xx | auth_failure | no_data`) → `UnavailableReason`
  enum. Used identically for the `Unavailable.reason()` and the
  `Served.staleReason()` paths.

### `lastUpdated` non-null fallback

Detail/card DTOs require `lastUpdated` non-null; the aggregator projection
records carry it nullable. When a projection's `lastUpdated()` is null, the
service substitutes the response's `as_of` (authorized by the merged Plan-01
DTO Javadoc).

## Route-specific assembly

- **`listVendorServices`** → `aggregator.toVendorServiceCards(integrations)` →
  map each `VendorServiceCard` → `VendorServiceCardDto`. Always 200.
- **`listVendors`** → `aggregator.toVendorCards(integrations)` → map each
  `VendorCard` → `VendorListEntryDto`. Always 200.
- **`getVendorServiceDetail(vendorServiceId)`** →
  `aggregator.toVendorServiceDetail(vendorServiceId, integrations)` →
  `Optional<VendorServiceDetail>`. See the 404 rule below.
- **`getVendorDetail(vendorId)`** →
  `aggregator.toVendorScopedView(vendorId, integrations)` →
  `Optional<VendorScopedView>`. Same 404 rule.

## 404-vs-partial-unavailability rule (both detail routes)

Stated precisely. Let `match` = the aggregator returned a present projection for
the requested id, and `unavailable` = `unavailable_products[]` is non-empty.

| `match` | `unavailable` | Result |
|---|---|---|
| present | — | 200 — assembled detail response |
| empty | non-empty | 200 — **empty-payload** detail response (synthesized header, empty `data_sources`/`vendor_services`, populated `unavailable_products`) |
| empty | empty | `Optional.empty()` → **404** (fresh AND stale both confirm the org has no integrations under the id) |

The middle row is the honesty guarantee: when the only product that could
contribute is unavailable and no stale exists, the Registry **cannot prove
emptiness**, so a 404 would be a false negative. It returns a 200 the UI
distinguishes from "really empty" by inspecting `unavailable_products[]`.

## Empty-payload detail response shape (the middle row)

The detail DTOs and openapi require all header fields non-null, but with zero
instances the service has no real values. It synthesizes a minimal, honest
header using the aggregator's **existing unknown-source convention** (already on
the wire for unmapped triplets in T08), rather than inventing new placeholders:

**`VendorServiceDetailResponse` (empty):**
- `vendorServiceId` = requested id (echoed — the one thing we know)
- `vendorServiceName` = requested id (no display name available)
- `vendorId` = `"unknown"`, `vendorName` = `"Unknown"`, `vendorCategory` = `"other"`
- `aggregateHealth` = `MISSING_DATA` (honest: "could not determine health")
- `lastUpdated` = `as_of`
- `dataSources` = `[]`, `unavailableProducts` = populated, `metadata` = computed

**`VendorDetailResponse` (empty):**
- `vendorId` = requested id (echoed)
- `vendorName` = `"Unknown"`
- `aggregateHealth` = `MISSING_DATA`
- `lastUpdated` = `as_of`
- `vendorServices` = `[]`, `unavailableProducts` = populated, `metadata` = computed

## Error handling

`VendorService` raises no HTTP errors (no HTTP vocabulary).

- **Per-product failure** → never throws; folds into `unavailable_products[]`
  (the coordinator already isolated it).
- **Not-found** → `Optional.empty()` return (controller → 404), not an
  exception.
- **Coordinator contract violation** (null `FetchResult`, non-`AdapterException`)
  → already surfaces as `IllegalStateException` from `fetchAll`; the service does
  **not** catch it; it propagates to Plan 03's `@ControllerAdvice` → 500.
- **Empty-but-unavailable detail** → synthesized minimal header (above).

## Components

| Component | Package | Change |
|---|---|---|
| `VendorService` | `service` | **New.** 4 route methods, shared spine, ctor(`FanOutCoordinator`, `VendorAggregator`, `Clock`). |
| `ServiceConfiguration` | `service` | **New.** `@Configuration` exposing `@Bean Clock systemUTC()`. |
| `OutboundAuth` | `auth` (new neutral pkg) | **New.** Framework-neutral header carrier wrapping `Map<String,String>`. Importable by controller, service, coordinator (no ArchUnit rule names `..auth..`). |
| `FanOutCoordinator` | `coordinator` | **Extend.** Add `fetchAll(String orgId, OutboundAuth auth)` overload that converts `OutboundAuth` → `HttpHeaders` internally. Keep the existing `HttpHeaders` overload and its tests. |
| `ProductOutcome.Served` | `coordinator` | **Extend.** Add `Optional<String> staleReason` with invariant `staleReason.isPresent() == stale`. |
| `OutcomeClassifier` | `coordinator` | **Extend.** Pass `Optional.of(reason)` at the stale-fallback construction site; `Optional.empty()` at the two non-stale sites. Reason still sourced from `AdapterException.reasonCode()` / `timeout` / `no_data` (ADR-001). |
| `VendorAggregator` | `aggregator` | **Extend.** Add `mappingVersion()` pass-through and a wire-category translation seam (returns contract-valid wire String for a given projection record). |
| `VendorServiceTest` | `service` (test) | **New.** Plain JUnit 5 + Mockito, fixed `Clock`. |

## ArchUnit additions

- A new `..auth..` package is introduced. No existing rule bans it from any
  layer, which is the point. Add a rule asserting `..auth..` depends on nothing
  internal (it is a leaf carrier) to keep it neutral.
- The existing service-layer bans remain green: `VendorService` imports neither
  `org.springframework.http..` nor `..mapping..`.

## Testing strategy

Plain JUnit 5, `@ExtendWith(MockitoExtension.class)`, **no Spring context** (RFC:
`VendorService` is tested with plain JUnit). Coordinator and aggregator are
mocked to return hand-built `ProductOutcome` / projection fixtures; assertions
are on the assembled DTO state, not mock interactions (TESTING.md: behavior over
interaction verification). `Clock` is a fixed clock, never mocked.

Coverage maps to the seven acceptance signals plus decided-rule edges:

1. **Oldest `fetched_at`** — `as_of` equals the min `fetchedAt` across `Served`.
2. **`cache_hit`** — true iff every contributing product is `Served` +
   `cacheHitPerProduct`; one stale/fetched/unavailable flips it false.
3. **`mapping_version`** — reflects `aggregator.mappingVersion()` on every
   successful assembly.
4. **Detail route, only product unavailable, no stale** — empty-payload 200
   shape with populated `unavailable_products[]`; never `Optional.empty()`.
5. **Detail route, fresh AND stale confirm empty** — aggregator returns
   `Optional.empty()` AND `unavailable_products[]` empty → `Optional.empty()`.
6. **All-adapter-failure** — empty top-level payload + one
   `unavailable_products[]` entry per failed product; `as_of = clock.instant()`.
7. **Stale vs omitted** — `Served(stale=true)` → entry with `stale:true` +
   `stale_since` + mapped `staleReason`; `Unavailable` → entry with `stale:false`
   + `reason`.

Plus: `lastUpdated` null → `as_of` fallback; the three remapped
`VendorCategory` → wire cases (`cloud_provider→cloud`, `identity→other`,
`notification→other`); the `IntegrationStatus → HealthState` 1:1 map.

## Non-goals (deferred per work plan)

- HTTP routing, status codes, header extraction, 401, `@ControllerAdvice` — Plan 03.
- DTO shapes and JSON serialization — Plan 01 (done).
- Adapter dispatch, timeouts, cache tiers, stale mechanics — T07.
- Grouping, `data_source_id`, rollup, unknown-source collapse — T08.
- Full-Spring-context integration testing — Plan 04.

## Resolved design decisions (carried from the execute-plan ambiguity gate)

- **A — Auth seam**: neutral `OutboundAuth` carrier + `FanOutCoordinator`
  overload; service stays HTTP-free.
- **B — `as_of` when empty**: injected `Clock`; `as_of = clock.instant()` when
  zero `Served`.
- **C — `lastUpdated` fallback**: fall back to `as_of` (DTO-contract authorized).
- **Version seam**: `VendorAggregator.mappingVersion()` pass-through.
- **Category mismatch**: aggregator-side wire translation;
  `cloud_provider→cloud`, `identity/notification→other`, rest 1:1.
- **Stale reason**: extend `ProductOutcome.Served` with `staleReason` (classifier
  already has it in scope at the stale construction site).
- **Empty detail header**: synthesize minimal header via the unknown-source
  convention; `aggregateHealth = MISSING_DATA`, `lastUpdated = as_of`.
