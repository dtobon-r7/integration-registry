# Design — Track 09 / Work Plan 01: Response contract and serialization

**Date**: 2026-06-04
**Engagement**: unified-integrations-view
**Track**: 09 — read-api-and-orchestration
**Work plan**: `engagements/unified-integrations-view/project/tracks/09-read-api-and-orchestration/work-plans/01-response-contract-and-serialization.md` (in the engagement repo, outside this service worktree)
**Mode**: Fresh execution
**Status**: approved (design)

---

## Outcome

The wire-format DTOs for all four read routes plus the shared envelope and error
types exist and serialize to JSON byte-for-byte against the locked
`decisions/rfc/openapi.json` contract. These controller-owned DTOs are the
serialization target that Work Plan 02 assembles into and Work Plan 03 returns.

This is a **contracts-first slice**: it defines the Registry's public JSON wire
shape as a self-contained DTO layer, deliberately separate from the internal
`aggregator/projection/` records (T08 produces) and the `FanOutCoordinator`
output (T07 produces). Per RFC-001 §Spring layer boundaries the controller owns
serialization. This plan defines the translation target; it does not populate it.

## Mental model

```
T07 FanOutCoordinator output ─┐
                              ├─► (Plan 02 assembly) ─► controller.dto.* ─► JSON wire (this plan defines the target)
T08 aggregator.projection.* ──┘
```

The projection records (`VendorServiceCard`, `VendorServiceDetail`,
`VendorScopedView`, `VendorCard`, `DataSourceDetail`, `IntegrationDetail`,
`IntegrationTypeCount`) are the aggregator's **output contract** consumed by
`VendorService`. They are NOT wire-safe:

- `IntegrationDetail` carries `dataSourceId` (internal FK) — absent from the wire
  `Integration` schema.
- `VendorScopedView` carries `vendorServicesCount` — absent from the wire
  `VendorDetailResponse` schema.
- All projection records use camelCase fields and raw Java enums
  (`IntegrationStatus`, `VendorCategory`).

This plan introduces the wire DTOs as the actual public surface. The projection
records become an internal source-of-values.

## Architecture

### Package layout

All DTOs live in a new self-contained package
`com.rapid7.integrationregistry.controller.dto`:

```
src/main/java/com/rapid7/integrationregistry/controller/dto/
├── package-info.java                 # documents this as the wire surface
├── HealthState.java                  # enum @JsonValue → healthy|warning|error|missing_data|disabled
├── ErrorCode.java                    # enum @JsonValue → UNAUTHENTICATED|NOT_FOUND|INTERNAL
├── UnavailableReason.java            # enum @JsonValue → timeout|upstream_5xx|auth_failure|no_data
├── IntegrationTypeCountDto.java
├── IntegrationDto.java
├── DataSourceDto.java
├── UnavailableProductDto.java
├── ResponseMetadataDto.java
├── ErrorEnvelopeDto.java             # nested ErrorBody(code, message)
├── VendorListEntryDto.java
├── VendorServiceCardDto.java         # flat feed — includes vendor_id/vendor_name
├── VendorServiceCardNestedDto.java   # vendor-scoped — omits vendor_id/vendor_name
├── VendorServicesResponse.java       # wrapper: vendor_services[] + envelope
├── VendorServiceDetailResponse.java  # wrapper: header + data_sources[] + envelope
├── VendorsResponse.java              # wrapper: vendors[] + envelope
└── VendorDetailResponse.java         # wrapper: vendor header + vendor_services[] + envelope
```

### Layer-boundary compliance

ArchUnit rule `controllerLayer_shouldNotDependOnInternalLayers`
(`LayerDependencyRules.java:13`) forbids `..controller..` from importing
`..coordinator..`, `..adapter..`, `..aggregator..`, `..mapping..`. Consequences:

- The DTO layer is **self-contained**. It must NOT reuse the existing
  `adapter.IntegrationStatus`, `mapping.VendorCategory`, or `mapping.ProductName`
  enums.
- DTO-local enums (`HealthState`, `ErrorCode`, `UnavailableReason`) are net-new
  in the `dto` package.

### Serialization mechanism — per-DTO annotation, no global config

snake_case is applied per-DTO via
`@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`. There is **no
global Spring Jackson config bean** and **no `@Configuration` class**.

Rationale (verified during design):

- The cache codec (`FetchResultCodec`) and bundle parser (`BundleParser`)
  construct their own private `ObjectMapper`/`JsonMapper` instances; they do not
  use the Spring-managed bean. A global naming strategy would not touch them —
  but localizing the contract to the `dto` package is still strictly safer and
  keeps the wire contract co-located with the types it governs.
- No `@Configuration` class means nothing for ArchUnit to catch and no global
  default that future controllers inherit implicitly.

Spring Boot's auto-configured ObjectMapper already provides the rest of the
needed behavior: ISO-8601 `Instant` rendering (`jackson-datatype-jsr310`, on the
classpath; `WRITE_DATES_AS_TIMESTAMPS` disabled by Boot default) and `ALWAYS`
inclusion (nulls render explicitly) by default.

### Nullability rules

| Field | Rule | Mechanism |
|---|---|---|
| `integration_label` | nullable → render **explicit JSON null** | default `ALWAYS` inclusion |
| `last_success_timestamp` | nullable → render **explicit JSON null** | default `ALWAYS` inclusion |
| `last_updated` (cards, detail) | nullable → render **explicit JSON null** | default `ALWAYS` inclusion (see decision below) |
| `stale_since` | **omitted** when absent; present only when `stale=true` | `@JsonInclude(NON_NULL)` on this field ONLY |

## Type catalog

Each record carries `@JsonNaming(SnakeCaseStrategy)`. Java fields camelCase; wire
snake_case. "Required" = non-null validated in the compact constructor (matching
the existing projection-record convention: `Objects.requireNonNull` with
`FIELD_*` constants, `List.copyOf` defensive copies). Nullable fields marked ▢.

### Enums (`@JsonValue` on a `String wire` field)

- `HealthState` → `healthy, warning, error, missing_data, disabled`
- `ErrorCode` → `UNAUTHENTICATED, NOT_FOUND, INTERNAL`
- `UnavailableReason` → `timeout, upstream_5xx, auth_failure, no_data`

Each enum exposes the wire token via `@JsonValue` and (for symmetry with the
existing `wireForm()` convention) a `fromWire(String)` lookup is optional and NOT
required by this plan — serialization is one-way here (Registry → client).

### Leaf DTOs

| Record | Fields | Notes |
|---|---|---|
| `IntegrationTypeCountDto` | `String integrationType, int total, int errorCount` | String per hybrid enum decision; invariants `total>=0`, `errorCount>=0`, `errorCount<=total` |
| `IntegrationDto` | `String integrationId, String integrationLabel▢, HealthState status, Instant lastSuccessTimestamp▢, String configurationUrl` | **No `data_source_id`** — internal FK, off the wire |
| `DataSourceDto` | `String dataSourceId, String displayName, String integrationType, String productName, HealthState status, int integrationsCount, List<IntegrationDto> integrations` | invariant `integrationsCount == integrations.size()` |
| `VendorListEntryDto` | `String vendorId, String vendorName, int vendorServicesCount` | invariant `vendorServicesCount >= 0` |
| `UnavailableProductDto` | `String productName, boolean stale, UnavailableReason reason, Instant staleSince▢` | `staleSince` is the ONLY `@JsonInclude(NON_NULL)` field |
| `ResponseMetadataDto` | `boolean cacheHit, Instant asOf, String mappingVersion` | |
| `ErrorEnvelopeDto` | `ErrorBody error` with nested `ErrorBody(ErrorCode code, String message)` | matches `{"error":{"code","message"}}` |

### Card DTOs

| Record | Fields |
|---|---|
| `VendorServiceCardDto` (flat) | `vendorServiceId, vendorServiceName, vendorId, vendorName, vendorCategory(String), integrationsConnected(int), List<IntegrationTypeCountDto> integrationTypeCounts, List<String> productsConnected, HealthState aggregateHealth, Instant lastUpdated▢` (10 fields) |
| `VendorServiceCardNestedDto` (vendor-scoped) | same as flat **minus** `vendorId`, `vendorName` (8 fields) |

`vendorCategory`, `integrationType`, `productName` are plain `String` per the
hybrid decision — they are bundle/value-driven and carry a known value mismatch
(see Open decisions). `productsConnected` is `List<String>`.

### Response wrappers

Each wrapper carries `List<UnavailableProductDto> unavailableProducts` and
`ResponseMetadataDto metadata` plus its payload:

| Record | Payload field |
|---|---|
| `VendorServicesResponse` | `List<VendorServiceCardDto> vendorServices` |
| `VendorServiceDetailResponse` | header (`vendorServiceId, vendorServiceName, vendorId, vendorName, vendorCategory(String), aggregateHealth(HealthState), lastUpdated▢`) + `List<DataSourceDto> dataSources` |
| `VendorsResponse` | `List<VendorListEntryDto> vendors` |
| `VendorDetailResponse` | `vendorId, vendorName, aggregateHealth(HealthState), lastUpdated▢` + `List<VendorServiceCardNestedDto> vendorServices` |

## Decisions

### Resolved (ambiguity gate)

1. **Enum strategy = Hybrid.** DTO-local typed enums (`HealthState`,
   `ErrorCode`, `UnavailableReason`) for Registry-owned stable sets; plain
   `String` for bundle/value-driven fields (`vendor_category`,
   `integration_type`, `product_name`).
2. **Naming = per-DTO `@JsonNaming`.** No global Spring config bean. (Refined
   from the gate's "global bean" default after verifying cache/bundle mappers
   are private and weighing ArchUnit/blast-radius.)
3. **Nullability = default `ALWAYS` + `@JsonInclude(NON_NULL)` on `stale_since`
   only.**
4. **Test strategy = `@JsonTest` slice + `json-schema-validator` against
   `openapi.json` component schemas + explicit null/absent assertions.**

### `last_updated` nullability (design call)

`openapi.json` marks `last_updated` as required on the cards and detail
responses, but the existing projection records (`VendorServiceCard`,
`VendorServiceDetail`, `VendorScopedView`) and the RFC entity notes treat it as
nullable ("null when no instance has yet recorded a successful timestamp").

**Decision**: model `lastUpdated` as **nullable, rendering explicit JSON null**,
consistent with the projection records that feed it. The DTO does not enforce
non-null because the value genuinely can be null at the source. This is a
faithful representation of the data, not a contract violation — the field is
always *present* (key emitted), satisfying "required" in the key-presence sense;
its value may be `null`. Recorded here so it is a deliberate, reviewable choice.

### `vendor_category` value mismatch (accepted risk, owned elsewhere)

`openapi.json` `VendorCategory` enumerates
`edr|siem|itsm|uem|cloud|productivity|other`; the existing Java
`mapping.VendorCategory` enum and KB 21.02 enumerate
`cloud_provider|identity|itsm|siem|edr|notification|other`. Only
`edr/siem/itsm/other` overlap.

**Decision**: `vendorCategory` is a plain `String` on the DTO. Plan 01 does not
reconcile the value sets — that is owned by T04 (bundle schema) and T08
(aggregator). Modeling it as String means this plan neither blocks on nor
prejudges the reconciliation. The serialization tests assert structural shape
(string present), not membership in either enum.

## Doc amendments (in scope)

The current `aggregator/projection/package-info.java` and ADR-003 both describe
the projection records as "the public surface of the read API serialized by the
controller layer." That wording now drifts: the controller DTOs are the wire
surface; the projection records are an internal source-of-values.

1. **`aggregator/projection/package-info.java`** — reword to state the records
   are the aggregator's internal output contract consumed by `VendorService`,
   and point to `controller.dto` as the wire surface. Keep the per-record route
   mapping (it is still accurate as the *origin* of each wire shape's values).
2. **ADR-003** — append a dated (2026-06-04) amendment note clarifying that
   "public surface of the read API" now refers to `controller.dto`; the
   projection records remain the aggregator's output contract, not the wire
   types. The PMD-suppression decision (the ADR's actual subject) is unchanged.

## Testing

`@JsonTest` slice, Docker-free, under
`src/test/java/com/rapid7/integrationregistry/controller/dto/`.

- **`@JsonTest`** auto-configures the real Spring Boot ObjectMapper — proving the
  production config (ISO-8601 `Instant`, `ALWAYS` inclusion) together with the
  per-DTO `@JsonNaming`/`@JsonInclude`. Tests use the injected
  `JacksonTester<T>`.
- **Schema conformance**: one test per response DTO (and one per supporting type)
  serializes a fully-populated instance, then validates the JSON against the
  matching `openapi.json` component schema via `com.networknt:json-schema-validator`
  (1.5.4, already a dependency). A small test helper `OpenApiSchemas` loads and
  caches the `openapi.json` document, registers `#/components/schemas` for `$ref`
  resolution, and exposes `validate(schemaName, jsonNode)` returning the
  validation message set (asserted empty).
- **Null-vs-absent assertions** (the contract's subtle bits, explicit per field):
  - `integration_label: null`, `last_success_timestamp: null`, `last_updated: null`
    → key **present** with JSON null.
  - `stale_since` → key **absent** when `stale=false`/null; **present** when
    `stale=true`.
  - `vendor_id`/`vendor_name` → present on flat card, **absent** on nested card.
  - Internal-only fields (`source_type`, `source_value`, `source_identifier`,
    `customer_account_id`, and `data_source_id` on `IntegrationDto`) → never
    present in any payload.
- **Enum spelling**: each enum serializes to its exact wire token.
- **Constructor-validation tests** mirroring `ProjectionRecordsTest`: NPE per
  required field (via `FIELD_*` constants), IAE on negative counts /
  `errorCount > total` / `count != list.size()`.

### Schema-validation caveat and fallback

`json-schema-validator` speaks JSON Schema; `openapi.json` is OpenAPI 3.1 (its
component schemas are JSON-Schema-2020-12 compatible). The helper validates
**individual component schemas** with the `#/components/schemas` map registered
for `$ref` resolution — not the whole OpenAPI document.

**Fallback (recorded so the chain does not improvise)**: if a 3.1-specific
keyword trips the validator for a given DTO during implementation, substitute a
targeted JSONAssert structural comparison (field names, nesting, null/absent
handling) against a hand-written expected-JSON fixture for that DTO, preserving
the byte-for-byte intent. Prefer schema validation; use JSONAssert only where the
validator cannot parse the schema.

## Verification

```bash
./mvnw spotless:apply   # GJF is authoritative — never hand-format
./mvnw verify           # build + tests + ArchUnit + PMD
```

The DTO tests are Docker-free; Docker is needed only for the unrelated T07 cache
Testcontainers tests (ADR-006).

**PMD watch**: `ExcessiveParameterList` will fire on the 10-field
`VendorServiceCardDto` and the multi-field `VendorServiceDetailResponse`.
Suppress locally with `@SuppressWarnings("PMD.ExcessiveParameterList")` and a
one-line justification ("N fields dictated by the RFC §… wire contract, not by
ergonomics"), matching the existing projection-record convention.

## Non-goals

- Assembly/computation of `as_of`, `cache_hit`, `unavailable_products[]`
  contents (Plan 02).
- HTTP routing, status-code selection, header handling (Plan 03).
- The aggregator projection records and rollup logic (T08).
- The coordinator failure-metadata types that map onto `unavailable_products[]`
  (T07).
- Brand/icon assets (UI responsibility per RFC).
- Reconciling the `vendor_category` enum value sets (T04/T08).
- A `fromWire(String)` deserialization path on the DTO enums (serialization is
  one-way: Registry → client).

## Acceptance signals

1. Each of the four route response DTOs serializes to JSON whose field names,
   nesting depth, enum values, and required/optional markers match the
   corresponding `openapi.json` schema.
2. Internal-only fields (`source_type`, `source_value`, `source_identifier`,
   `customer_account_id`, plus `data_source_id` on `IntegrationDto`) never appear
   in any serialized payload.
3. `stale_since` is emitted only when an `unavailable_products[]` entry has
   `stale: true`; absent otherwise.
4. Nullable fields `integration_label` and `last_success_timestamp` serialize as
   JSON null (not omitted, not empty string) when null.
5. The vendor-scoped nested card omits `vendor_id`/`vendor_name`; the flat card
   includes them.
6. `ResponseMetadataDto`, `UnavailableProductDto`, `IntegrationTypeCountDto`, and
   `ErrorEnvelopeDto` each serialize to their `openapi.json` shape.
7. `./mvnw verify` passes (ArchUnit layer boundaries, PMD, Spotless).
