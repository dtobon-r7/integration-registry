# Design — InsightIDRAdapter (Track 06, Work Plan 01)

**Date**: 2026-06-25
**Work plan**: `engagements/unified-integrations-view/project/tracks/06-idr-adapter/work-plans/01-idr-adapter.md`
**Branch**: `worktree-track-06-wp-01`
**Mode**: Fresh execution

---

## Outcome

An `InsightIDRAdapter` fetches Microsoft Defender event sources via the InsightIDR
two-call search→detail pattern and emits `NormalizedIntegration` records with the full
RFC-001 status mapping, asymmetric `productType`/`productName` source resolution, and a
templated `configuration_url` — covered by pinned fixtures for every status path and
contract tests on the established `MockRestServiceServer` pattern. Codes against the
cloned-source CMS DTO shape and verifies fully offline. Live verification is Plan 02.

## Mental model

This is the second MVP adapter and the proof that the `IntegrationAdapter` seam holds for
a meaningfully different product API than ICON's. ICON is one call returning a flat
connection list with health inline. IDR is a **two-call pattern**: a search call returns a
lightweight list with no health, then **one detail call per event source** supplies the
health signals. The adapter owns a bounded-concurrency detail-fetch step internally, so the
N+1 cost is contained inside the adapter and never escapes to the T07 coordinator.

The worked reference is `InsightConnectAdapter` (`adapter/insightconnect/`): same contract,
same exception-mapping discipline, same fixture + contract-test pattern — only the call
shape, the source-identifier rule, and the status signals differ. The hard lesson carried
from ICON (Q15 / Plan 03) is **mock-vs-live wire-shape drift**: ICON's adapter was built
against an assumed shape only the Prism mock matched, and needed a corrective plan once it
met the live product. This plan front-loads that verification: it codes against the real CMS
DTO shape readable in the cloned `cloud-monitoring-service-ui` source today, and re-bases the
Prism mock onto that shape.

## Grounding references (loaded)

- **RFC-001 §InsightIDRAdapter** (L919–966) — two endpoints, the 5-row status table, the
  asymmetric `productType`-preferred / `productName`-fallback rule, `configuration_url`
  template `{idr_base}/eventsources/{id}`, null-`lastActive` rule, bounded detail-call pool.
- **RFC-001 §`source_type` enum** (L810–828) — `product_type` (primary) + `product_name`
  (fallback) are the closed-enum values; both already exist in `mapping/SourceType.java`.
- **RFC-001 §Status precedence** (L832–848) — `error > missing_data > warning > disabled > healthy`.
- **RFC-001 §Fan-out defaults** (L626–646) — IDR per-adapter timeout 15s; **internal detail
  concurrency 60**; **per-detail-call timeout 2s**. All configurable; staging tuning out of plan.
- **ADR-002** — adapters use Spring `RestClient` (not WebClient); blocking calls; async is
  T07's concern. `spring.threads.virtual.enabled=true` already set in `application.yaml`.
- **KB 22.03** (IDR/MDR dossier) — verified CMS endpoints + two-call trace.
- **KB 21.02** (schema) — `NormalizedIntegration` field semantics.
- **Live CMS source** (`cloud-monitoring-service-ui`) — authoritative wire shape:
  `EventSourceController` (search returns bare `List<EventSourceSearchDto>`, requires
  `?query=`; detail returns `EventSourceDetailsDto`); `EventSourceSearchDto`
  (id, rrn, name, productType, productName, matchedOn, executorInfo);
  `EventSourceDetailsDto` (status non-null String, lastActive nullable Long epoch-ms, issue);
  `EventSourceIssueDto` (severity, message, eventTime).
- **IDR mock** (`mocks/platform/idr-eventsources/`) — target of the re-base.

## Resolved decisions (ambiguity gate, 2026-06-25)

1. **`configuration_url`** = RFC template `{idr_base}/eventsources/{id}` (id = search DTO `id`);
   prefer an API-returned URL when present. RFC is authoritative over the dossier SSR form
   (`{insight_domain}/s/{orgId}/eventSources/{rrn}`), which is recorded as a Plan 02
   live-verification candidate.
2. **Status mapping** = fixture-pinned, case-insensitive over the known string set; defensive
   default for unknown status → `missing_data` + WARN (mirrors `ConnectionStatusMapper`).
3. **`source_value`** = raw `productType` verbatim (`"MICROSOFT_DEFENDER"`). No normalization in
   the adapter. The offline "unknown vendor mapping" WARN is expected — vendor resolution is
   T08; bundle reconciliation (`microsoft-defender-endpoint` seed vs raw `MICROSOFT_DEFENDER`)
   is **Plan 02**.
4. **Staleness threshold** = 24h default, configurable at `integration-registry.insightidr.staleness-threshold`.
5. **Concurrency knobs** = RFC starting points: `detail-concurrency=60`, `detail-timeout=2s`,
   per-adapter/search `timeout=15s`. All configurable; staging tuning out of plan.
6. **Per-source detail failure** (timeout/5xx on one of N detail calls) → that one event source
   maps to `missing_data` + WARN; the rest return normally. Auth (401/403) on any call is
   systemic → fail the whole fetch as `AdapterAuthException`.

## Accepted risks

- **Q1** (Kong consumer registration) — affects only cloud-staging exercise, not fixture tests
  or the mock-stack smoke. Tracked at engagement level.
- **Bundle `source_value` case/format mismatch** — deferred to Plan 02 by design (this plan's
  Non-goals).

---

## Architecture

A new `adapter/insightidr/` package mirroring `adapter/insightconnect/`, auto-discovered by
`FanOutCoordinator` via its `Set<IntegrationAdapter>` injection (no coordinator change). Units:

| Unit | Stereotype | Responsibility | Depends on |
|------|-----------|----------------|------------|
| `InsightIDRAdapter` | `@Component` | Orchestrates the two-call flow; owns exception mapping | RestClient, mapper, fetcher, properties |
| `EventSourceStatusMapper` | `@Bean` (pure) | String→5-state table, precedence, null/stale rules, `last_success_timestamp` | nothing (no Spring, no clock) |
| `BoundedDetailFetcher` | plain class | Generic bounded-concurrency fan-out helper | `Semaphore` + virtual-thread executor |
| `EventSourceSearchDto` / `EventSourceDetailsDto` / `EventSourceIssueDto` | records | Inbound Jackson DTOs, minimal, live-shape-faithful | Jackson (see note) |
| `InsightIDRProperties` | `@ConfigurationProperties` | `baseUrl`, `idrBase`, `timeout`, `stalenessThreshold`, `detailConcurrency`, `detailTimeout` | — |
| `InsightIDRClientConfig` | `@Configuration` | RestClient bean (+ pooled HttpClient) + mapper `@Bean` | properties |

## Data flow

```
fetch(orgId, authHeaders):
  1. GET /api/3/organizations/{orgId}/eventsources/search?query=
       -> List<EventSourceSearchDto>          # bare JSON array (confirmed: CMS controller L64)
  2. BoundedDetailFetcher.fetchAll(results, detailConcurrency, detailTimeout, src ->
       GET /api/3/organizations/{orgId}/eventsources/{src.id}
         -> EventSourceDetailsDto
       normalize(src, detail, orgId))          # per-source detail failure -> missing_data + WARN
  3. return FetchResult(integrations, fetchedAt)   # fetchedAt captured once at fetch start
```

- `orgId` is a **path segment** (RFC note); identity headers still forwarded for auth.
- **Source identifier**: `productType` non-blank → `("product_type", productType)` verbatim;
  else → `("product_name", productName)` + WARN carrying `eventSourceId`, `name`, `productName`.

**Jackson note**: model the inbound DTOs as records with `@JsonIgnoreProperties(ignoreUnknown = true)`,
exactly as `adapter/insightconnect/ConnectionViewModel` / `Plugin` do. That annotation is from
`com.fasterxml.jackson.annotation.*` — a **core** annotation, unchanged in Jackson 3. Only `databind`
annotations (e.g. `@JsonDeserialize`) moved to `tools.jackson.databind.*`; the inbound DTOs need no
databind annotations, so they import `com.fasterxml.jackson.annotation.JsonIgnoreProperties` like ICON.
`ignoreUnknown` is essential here: the live `EventSourceDetailsDto` carries ~17 fields and the registry
DTO models only the few it reads.
- `integration_label` = search DTO `name`; `integration_type` = `"SIEM Event Source"`;
  `configuration_url` = API URL if present else `{idrBase}/eventsources/{id}`.

## Status derivation (`EventSourceStatusMapper`)

Pure, case-insensitive, applied in precedence order `error > missing_data > warning > disabled > healthy`.
Signature: `deriveStatus(String status, EventSourceIssueDto issue, Long lastActive, Instant now, Duration threshold)`.
The reference instant `now` is **injected** (the fetch instant), never `Instant.now()` inside the
mapper — deterministic for fixtures (mock `lastActive` values are already >24h before the test date).

```
null lastActive                              -> missing_data      (always, regardless of threshold)
status ~ {error, failed, fault, errored}     -> error
issue present AND severity ~ {error,critical,fatal,high} -> error
issue present (other severity)               -> warning
status ~ {paused, disabled, inactive, stopped} -> disabled
lastActive older than threshold              -> missing_data
status ~ {active, running, healthy, ok, enabled} AND lastActive within threshold -> healthy
unrecognized status string                   -> missing_data + WARN (eventSourceId logged)
```

- `last_success_timestamp` = `Instant.ofEpochMilli(lastActive)` when non-null, else null.
- Per-source detail-fetch failure is handled in the adapter (not the mapper): the source maps to
  `missing_data` + WARN. The mapper itself is only reached when a detail DTO was retrieved.
- **Judgment call (recorded)**: RFC says "issue non-null and non-fatal → warning", implying a
  fatal issue → error. The severity→error/warning split implements that. The exact severity
  vocabulary is pinned by fixtures here and confirmed against live in Plan 02.

## Failure handling

| Failure | Maps to | Surfaced as |
|---------|---------|-------------|
| Search timeout / transport | `AdapterTimeoutException` (transient) | `unavailable_products[].reason = timeout` |
| Search 5xx | `AdapterUpstreamException` (transient) | `reason = upstream_5xx` |
| Search 4xx other | `AdapterUpstreamException` | `reason = upstream_5xx` |
| Any 401/403 (search or detail) | `AdapterAuthException` (non-transient) | `reason = auth_failure` |
| One detail call timeout/5xx (of N) | that source → `missing_data` + WARN | source visible, IDR stays available |

Per-source `missing_data` slightly overloads the state (it normally means "configured, no data"),
but it is the closest in-band signal and avoids the silent-omission failure the project exists to
eliminate. Recorded as an intentional choice for the Plan 02 / aggregator review.

## Testing

TDD; mirrors `insightconnect` layering and `TESTING.md`.

- **`EventSourceStatusMapperTest`** (pure unit): every status path; null `lastActive` → `missing_data`
  regardless of threshold; stale vs fresh against a pinned `now`; issue severity → error/warning;
  unknown status → `missing_data` + WARN; `last_success_timestamp` conversion.
- **`InsightIDRAdapterContractTest`** (`MockRestServiceServer`): stub search + per-id detail; assert
  full `NormalizedIntegration` per fixture; `product_type` preferred even when non-canonical;
  `product_name` fallback + WARN when `productType` absent; per-source detail-failure → `missing_data`;
  exception mapping (search timeout/5xx/auth; detail 401 → whole-fetch auth). End every test with
  `server.verify()`.
- **`BoundedDetailFetcher` concurrency test**: a multi-source run asserts max in-flight ≤ configured
  pool size (e.g. 50 sources, pool 5 → never >5 concurrent), via an instrumented counter.
- **`InsightIDRPropertiesTest`**: binding + validation (blank baseUrl/idrBase reject; defaults applied).
- **Fixtures** `src/test/resources/fixtures/insightidr/`: `search-list.json`, `search-multi.json`
  (concurrency), per-status detail fixtures (`detail-healthy/warning/error/disabled`),
  `detail-null-lastactive`, `detail-stale`, and `search-producttype-absent` (WARN-on-fallback).
- **Mock re-base** `mocks/platform/idr-eventsources/openapi.yaml` + `README.md`: correct `issue` to
  the live `{severity, message, eventTime}` shape (currently `{code, message}`), align the detail DTO
  fields to live `EventSourceDetailsDto`, and correct the README. This closes the drift the plan targets.

`mvn verify` (ArchUnit + PMD + Spotless + Docker-backed Valkey cache tests) stays green.

## Out of scope (deferred)

Vendor/vendor-service resolution (T08); fan-out / cache / retry / stale-tier (T07);
bulk-detail endpoint + cache-warmer (Phase 1.5); live-API verification + bundle reconciliation
(Plan 02); concurrency/timeout tuning against staging (pre-prod); Cloud Webhooks / Collectors
surfaces; the canonical `Class3HeaderAttacher` (T10 — identity-header forwarding hand-rolled with
a marker until then).
