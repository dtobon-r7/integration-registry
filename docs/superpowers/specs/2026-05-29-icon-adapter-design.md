# Design — InsightConnect adapter, fixtures, and contract tests

**Work plan**: [`engagements/unified-integrations-view/project/tracks/05-adapter-interface-and-icon/work-plans/02-icon-adapter.md`](../../../../../engagements/unified-integrations-view/project/tracks/05-adapter-interface-and-icon/work-plans/02-icon-adapter.md)
**Track**: 05 — Adapter interface and InsightConnect adapter
**Branch**: `worktree-track-05-wp-02`
**Date**: 2026-05-29

---

## Outcome

Land the first concrete `IntegrationAdapter`: an `InsightConnectAdapter` that fetches automation connections from `GET /api/public/v1/connections?includeTests=1`, parses the `ConnectionViewModel` records, and emits one `NormalizedIntegration` per connection with the full RFC-001 status mapping, `integration_type = "Automation Plugin"`, `integration_label = null`, `source_identifier = (plugin_name, plugin.name)`, a templated `configuration_url`, and a derived `last_success_timestamp`. Covered by a pure-unit-tested status mapper and `MockRestServiceServer` contract tests over pinned JSON fixtures — one per status path plus the precedence, stale, and multi-connection cases.

This is the higher-confidence of the two MVP adapters (verified against local ICON source) and the worked example for the IDR adapter (T06) and the SurCom fast-follow (T12).

## Key decisions (resolved upstream)

These were settled before this design and are not re-litigated here:

- **HTTP client is Spring `RestClient`, not `WebClient`** — recorded in [ADR-002](../../../../../engagements/unified-integrations-view/decisions/adr/ADR-002-use-restclient-for-adapters.md). RFC-001 §InsightConnectAdapter names `WebClient`; that was the generic pre-implementation placeholder. The service is servlet-based (no `spring-webflux`), Spring Framework 7 makes `RestClient` the recommended synchronous client (`RestTemplate` deprecated), and the T03 contract-test pattern binds `MockRestServiceServer` to a `RestClient.Builder` — which a reactive `WebClient` cannot do. Async fan-out is **not** the adapter's concern; it lives at the T07 `FanOutCoordinator` (`@Async`/`CompletableFuture` per adapter, with virtual threads enabled).
- **Package**: a new subpackage `com.rapid7.integrationregistry.adapter.insightconnect`. The shared contract types from plan 01 stay in `adapter/`.
- **Status derivation is a pure class** (`ConnectionStatusMapper`) with no Spring/HTTP dependency — unit-testable in <100ms.
- **Outbound headers**: forward the `HttpHeaders` passed into `fetch()` onto the outbound request, with a `TODO(T10)` marker for the future `Class3HeaderAttacher` swap. Contract tests do not assert outbound headers — T10 owns that surface.
- **Scope**: adapter + fixtures + contract tests (the in-CI bar). Live manual smoke (work-plan acceptance signals 9–10, against `uiv-mock-stack` and live `soar/komand`) is deferred to T01's verification loop and noted as a follow-up.

## Architecture

Everything new lives under `com.rapid7.integrationregistry.adapter.insightconnect`. The package depends only on the JDK, Spring (`org.springframework.web.client.RestClient`, `org.springframework.http.HttpHeaders`, `org.springframework.boot.context.properties`), Jackson (binding), and the plan-01 contract types in the parent `adapter` package. It depends on **no** other internal Registry layer — ArchUnit's `adapterLayer_shouldNotDependOnInternalLayers` already enforces this, and `adapter → org.springframework.web..` is permitted.

The design separates three concerns so each is independently testable:

1. **Transport + orchestration** (`InsightConnectAdapter`) — makes the one HTTP call, translates failures to adapter exceptions, walks the response, and assembles `NormalizedIntegration` records. Needs Spring + the network (a `MockRestServiceServer` in tests).
2. **Health derivation** (`ConnectionStatusMapper`) — pure logic: the 5-row status table, instance-level precedence, most-recent-test selection, and `last_success_timestamp` derivation. No Spring, no HTTP, no clock.
3. **Wire shape** (response DTOs) — Jackson-bound records mirroring the real ICON JSON.

## Components

All under `src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/`.

### `InsightConnectAdapter` — `@Component implements IntegrationAdapter`

- `productName()` → `"InsightConnect"` (a package-private `static final String` constant; RFC-001 §Canonical `productName()` values).
- `fetch(String orgId, HttpHeaders authHeaders)`:
  1. Issues `GET {base-url}/api/public/v1/connections?includeTests=1` via an injected `RestClient`, copying the inbound `authHeaders` onto the request. A `// TODO(T10): replace hand-rolled identity-header forwarding with Class3HeaderAttacher` marker sits at this exact line.
  2. Deserializes the body into `ConnectionsResponse`.
  3. Maps each `ConnectionViewModel` → `NormalizedIntegration` (see Data Flow).
  4. Returns `FetchResult(List<NormalizedIntegration>, Instant.now())`.
- Trusts that `orgId` and `authHeaders` are well-formed — the controller boundary owns request validation (plan 01's documented deferral). `customerAccountId` is set to `orgId` verbatim.
- Exception translation lives here (see Error Handling).

The adapter is constructed with a ready `RestClient` (built from `InsightConnectProperties`) plus the `ConnectionStatusMapper`. A small `@Configuration` (`InsightConnectClientConfig`) exposes the `RestClient` bean from a `RestClient.Builder`, so the contract test can `bindTo(builder)` a `MockRestServiceServer` before the client is built.

### `ConnectionStatusMapper` — pure class

Two responsibilities, both pure:

- `IntegrationStatus deriveStatus(String orchestratorStatus, ConnectionTest mostRecentTest)` — `mostRecentTest` may be `null` (no tests). Implements the algorithm below.
- `Instant deriveLastSuccess(List<ConnectionTest> tests)` — the most recent test with `status == "success"`, by `createdAt`; `null` if none.

A helper `ConnectionTest mostRecentByCreatedAt(List<ConnectionTest> tests)` (used by the adapter to pick the test fed to `deriveStatus`) also lives here so the selection rule is defined once. Null/empty lists return `null`.

### `InsightConnectProperties` — `@ConfigurationProperties(prefix = "integration-registry.insightconnect")` record

Mirrors `mapping/loader/VendorMappingProperties`. Fields:

- `baseUrl` — host+scheme of the ICON connections API (used to build the request URL).
- `iconBase` — base for `configuration_url` templating (`{iconBase}/automation/connections/{id}`).
- `timeout` — `Duration`, the per-call read/connect timeout configured on the `RestClient`'s request factory.

Bound under the existing `integration-registry:` namespace in `application.yaml`. No stale defaults for environment-specific values — absent config fails fast at binding, consistent with the vendor-mapping convention.

### Response DTOs — Jackson-bound records

Mirror the real wire shape from `mocks/platform/icon-connections/openapi.yaml` (authoritative over RFC prose). Field names match the JSON exactly (`connectionTests`, not `connection_test`):

- `ConnectionsResponse(List<ConnectionViewModel> data, ResponseMetadata metadata)`
- `ConnectionViewModel(String id, String name, Plugin plugin, Orchestrator orchestrator, Boolean isCloud, String createdAt, String updatedAt, List<ConnectionTest> connectionTests, String configurationUrl)` — `configurationUrl` is nullable; today's API does not return it, but the adapter prefers it when present (RFC-001 forward-compat clause).
- `Plugin(String name, String pluginVendor, String pluginVersion)`
- `Orchestrator(String id, String name, String status, String version)`
- `ConnectionTest(String id, String connectionId, String status, Boolean isStale, String errorMessage, Instant createdAt)`
- `ResponseMetadata(Integer total)`

`@JsonIgnoreProperties(ignoreUnknown = true)` on the DTOs so unexpected fields don't break parsing. `createdAt` on `ConnectionTest` binds to `Instant` (ISO-8601 in the fixtures) so `most-recent-by-createdAt` is a clean `Instant` comparison.

## Data flow — `fetch`

```
fetch(orgId, authHeaders)
  → RestClient GET {baseUrl}/api/public/v1/connections?includeTests=1
        (copy authHeaders onto the request)   // TODO(T10): Class3HeaderAttacher
  → deserialize → ConnectionsResponse
  → for each ConnectionViewModel cvm:
        selectedTest = mapper.mostRecentByCreatedAt(cvm.connectionTests)   // may be null
        status       = mapper.deriveStatus(cvm.orchestrator.status, selectedTest)
        lastSuccess  = mapper.deriveLastSuccess(cvm.connectionTests)       // may be null
        configUrl    = (cvm.configurationUrl != null && !blank)
                          ? cvm.configurationUrl
                          : iconBase + "/automation/connections/" + cvm.id
        → NormalizedIntegration(
              integrationId      = cvm.id,
              sourceIdentifier   = (sourceType="plugin_name", sourceValue=cvm.plugin.name),
              productName        = "InsightConnect",
              integrationType    = "Automation Plugin",
              integrationLabel   = null,
              status             = status,
              lastSuccessTimestamp = lastSuccess,
              configurationUrl   = configUrl,
              customerAccountId  = orgId)
  → FetchResult(list, Instant.now())
```

Status derivation uses the single most-recent test; `last_success_timestamp` independently scans for the most-recent *successful* test. The two selections are deliberately separate: a connection whose latest test failed still reports the timestamp of its last success.

## Status derivation algorithm

First-match wins; precedence `error > missing_data > warning > disabled > healthy`. Let `t` = the most-recent `ConnectionTest` by `createdAt` (may be absent), `orch` = `orchestrator.status`:

| # | Result | Condition |
|---|---|---|
| 1 | `error` | `t != null && t.status ∈ {failed, timeout}` **OR** `orch == "error"` |
| 2 | `missing_data` | `t != null && t.isStale == true` **OR** `orch == "unknown"` |
| 3 | `warning` | `orch == "warning"` |
| 4 | `disabled` | `orch == "stopped"` |
| 5 | `healthy` | `orch == "healthy"` **AND** `t != null && t.status == "success" && t.isStale == false` |
| 6 | `missing_data` | `orch == "healthy"` but no confirming healthy test (no test, `pending` test, etc.) |

The orchestrator enum is exhaustive (`healthy | error | warning | stopped | unknown`), so rows 1–6 cover every `(orch, test)` combination — **no silent default**.

**No-test case**: a healthy orchestrator with no test signal resolves to `missing_data` (row 6) — we cannot confirm the connection works, which is exactly the `missing_data` "insufficient data / silent failure" semantics. Such a connection never reports `healthy` because row 5 requires a confirming successful, non-stale test.

**Unrecognized orchestrator value**: if ICON introduces a sixth orchestrator status, an unmatched value falls to `missing_data` with a `WARN` log naming the unexpected status value — so one anomalous connection cannot fail the whole fetch. (The mapper is pure and has no connection id; the adapter's own skip-path WARNs carry the id.) This is a documented fallback, not a silent default; it is unit-tested.

## Error handling

`RestClient` `.retrieve()` with an `onStatus` handler, wrapped in a try/catch around execution. All translations preserve the cause chain via the `(String, Throwable)` constructors:

| Upstream condition | Adapter exception | `reasonCode()` |
|---|---|---|
| Connect/read timeout (`ResourceAccessException` wrapping `SocketTimeoutException`/`ConnectException`) | `AdapterTimeoutException` | `timeout` |
| 5xx | `AdapterUpstreamException` | `upstream_5xx` |
| 401 / 403 | `AdapterAuthException` | `auth_failure` |
| Other 4xx | `AdapterUpstreamException` | `upstream_5xx` |
| Body present but unparseable | `AdapterUpstreamException` | `upstream_5xx` |

Rationale for "other 4xx → upstream": the contract exposes exactly three failure modes, and a malformed/unexpected 4xx (e.g. 400, 404) signals the upstream contract is broken rather than an auth problem — `upstream_5xx` (transient, stale-eligible) is the closest honest classification. There is no swallowed-exception path: every catch either rethrows as an adapter exception (cause attached) or the success path returns a `FetchResult`. This keeps PMD's empty-catch and the project's silent-failure conventions satisfied.

## Configuration

`application.yaml`, under the existing `integration-registry:` namespace:

```yaml
integration-registry:
  insightconnect:
    base-url:   # ICON connections API host — from deploy environment, no default
    icon-base:  # base for configuration_url deep-links — from deploy environment, no default
    timeout: 5s # per-call read/connect timeout (starting value; T07 owns the fan-out deadline)
```

`base-url` and `icon-base` carry no in-code defaults — absent config fails fast at property binding, matching the vendor-mapping convention. `timeout` has a conservative default that the T07 coordinator's deadline supersedes operationally.

## Testing

Per `TESTING.md`: pure logic → unit tests; upstream-JSON parsing → adapter contract tests (`RestClient` + `MockRestServiceServer` + `FixtureLoader`).

### `ConnectionStatusMapperTest` (pure unit, <100ms each)

- Each of the five statuses via its primary trigger.
- Precedence: `failed` test + `warning` orchestrator → `error`.
- `isStale == true` → `missing_data` (regardless of orchestrator).
- `orchestrator == unknown` → `missing_data`.
- `orchestrator == healthy` + no test → `missing_data` (row 6).
- `orchestrator == healthy` + `pending` test → `missing_data`.
- `orchestrator == stopped` → `disabled`.
- Unrecognized orchestrator value → `missing_data`.
- `mostRecentByCreatedAt`: picks the latest of multiple tests; returns `null` for empty/null.
- `deriveLastSuccess`: most-recent success `createdAt`; `null` when no success exists; picks the latest success when the latest *overall* test is a failure.

### `InsightConnectAdapterContractTest` (`MockRestServiceServer` + `FixtureLoader`)

Bind `MockRestServiceServer` to the `RestClient.Builder` before building the client; stub `GET .../connections?includeTests=1`; always end with `server.verify()`.

- One test per single-connection fixture asserting the **full** `NormalizedIntegration`: `status`, the `(plugin_name, plugin.name)` pair, `integrationLabel == null`, `integrationType == "Automation Plugin"`, the templated `configurationUrl`, and `lastSuccessTimestamp`.
- `productName()` returns `"InsightConnect"`.
- Multi-connection fixture → a list of the expected size with per-connection assertions (no reliance on element order — assert via `extracting`/`filteredOn`).
- `configuration_url`: templated when the response omits a URL; API-returned URL preferred when present (a dedicated fixture variant or an inline-stubbed body).
- Exception mapping: stub a read timeout → `AdapterTimeoutException`; 503 → `AdapterUpstreamException`; 401 and 403 → `AdapterAuthException`; 400/404 → `AdapterUpstreamException`. Assert `reasonCode()` and that the cause is attached.

### Fixtures — `src/test/resources/fixtures/insightconnect/`

`error.json`, `missing-data-stale.json`, `missing-data-unknown.json`, `warning.json`, `disabled.json`, `healthy.json`, `precedence-failed-and-warning.json`, `multi-connection.json` (mirrors the mock: `jira` healthy + `jira` warning/stale + `microsoft-defender` healthy). Fixtures stay trimmed to the fields the adapter reads (<5KB), and reuse the mock's UUID/timestamp shapes for realism.

### Quality gates

`mvn verify` stays green: JUnit, ArchUnit (`adapter` package respects layer boundaries), PMD (no empty catches, no unused code, no placeholder stubs — the `TODO(T10)` marker annotates working code and is acceptable).

## Out of scope (deferred)

- Vendor / vendor-service resolution (T08) — the adapter emits the raw `(plugin_name, plugin.name)` pair only.
- Fan-out, cache, retry, total-deadline (T07) — the adapter has a per-call timeout but no retry and no cache awareness.
- The canonical `Class3HeaderAttacher` (T10) — hand-rolled identity-header forwarding with a `TODO(T10)` marker until then.
- Live-API smoke (work-plan signals 9–10) — T01's verification loop owns it; tracked as a follow-up.
- IDR (T06) and SurCom (T12) adapters.
