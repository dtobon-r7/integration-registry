# Design — FanOutCoordinator and structured per-product output

**Work plan**: `engagements/unified-integrations-view/project/tracks/07-fan-out-and-cache/work-plans/02-fan-out-coordinator.md`
**Date**: 2026-06-02
**Status**: Approved (brainstorming)

---

## Outcome

A `FanOutCoordinator` (`@Component`) dispatches all registered `IntegrationAdapter`s in
parallel under a total request deadline, isolates per-adapter failures, applies the
stale-tier fallback decision tree against plan 01's two-tier Valkey cache, and returns a
structured per-product output (`ProductOutcome`) that T09's `VendorService` serializes into
`unavailable_products[]` and `metadata`.

This is the request-time orchestration seam between the adapters (T05/T06) and the read API
(T09). It is the T07 → T09 stagger point: `ProductOutcome` is the contract T09 assembles from.

## Grounding references

- RFC-001 §Fan-out coordinator — concurrency model (one async task per adapter, no cap at
  MVP), per-adapter timeout, total deadline = `max(per-adapter) + headroom`, no retry, the
  4-step stale-fallback decision tree.
- RFC-001 §Operational defaults — ICON 5s / IDR 15s / total 20s starting points (Q5; numbers
  deferred to staging, shape pinned, every value per-environment configurable).
- RFC-001 §Supporting types — `UnavailableProduct` (`product_name`/`stale`/`reason`/`stale_since`)
  and `ResponseMetadata` (`cache_hit`/`as_of`) — the T09 shapes `ProductOutcome` must feed.
- RFC-001 §Request lifecycle — cache-hit / cache-miss / adapter-unavailable branches.
- ADR-001 — the `AdapterException` family parent; dispatch on `reasonCode()` / `isTransient()`,
  never re-derive `reason` at the catch site.
- ADR-002 — adapters are blocking `RestClient`; parallel dispatch requires
  `spring.threads.virtual.enabled=true` (hard setup step for this plan).
- Plan 01 cache (`IntegrationCache`): `readFresh` → `Optional<FetchResult>`, `readStale` →
  `Optional<StaleEntry>` (carries `result` + `staleSince`), `writeOnSuccess`. Reads are total
  (Valkey outage = empty, never throws); writes swallow failures.
- T05 contract: `IntegrationAdapter.productName()` + `fetch(orgId, HttpHeaders) throws
  Adapter{Timeout,Auth,Upstream}Exception`; `FetchResult(List<NormalizedIntegration>, Instant
  fetchedAt)`.

## Resolved design decisions (ambiguity gate)

1. **Empty-but-successful fetch** — work-plan literal: empty + no usable stale →
   `Unavailable(reason="no_data", stale=false)`, and the empty-success is **not** written to
   cache (so `no_data` isn't masked by a later fresh hit); empty + stale-in-window → serve
   stale.
2. **Total-deadline cutoff** (adapter still in flight, never threw) → synthesize
   `reason="timeout"`, treat as transient → stale-tier fallback applies, identical handling to
   a per-adapter timeout. (RFC `reason` enum is closed; reusing `timeout` avoids an amendment.)
3. **Output type** — sealed `ProductOutcome` with `Served` / `Unavailable` variants; illegal
   states unrepresentable.
4. **Async dispatch** — coordinator-owned `Executors.newVirtualThreadPerTaskExecutor()`; one
   task per adapter; per-adapter timeout and total deadline both enforced via
   `future.get(remaining, MILLIS)`.

## Architecture & boundary

`FanOutCoordinator` lives in `com.rapid7.integrationregistry.coordinator`. Per the ArchUnit
rule `coordinatorLayer_shouldNotDependOnDisallowedLayers`, it depends **only** on `cache`
(`IntegrationCache`) and `adapter` (`IntegrationAdapter`, `FetchResult`, `NormalizedIntegration`,
the `AdapterException` family). It must not touch `controller`, `service`, `aggregator`, or
`mapping`.

Injected collaborators: the autowired `Set<IntegrationAdapter>` (`List` is also acceptable —
order does not matter), `IntegrationCache`, `CoordinatorProperties`.

Public surface — one method:

```java
List<ProductOutcome> fetchAll(String orgId, HttpHeaders authHeaders)
```

T09's `VendorService` calls this and assembles the response from the returned outcomes.

## The structured output type — `ProductOutcome`

```java
public sealed interface ProductOutcome permits ProductOutcome.Served, ProductOutcome.Unavailable {

    String productName();

    record Served(
        String productName,
        List<NormalizedIntegration> integrations,
        Instant fetchedAt,
        boolean cacheHitPerProduct,    // true only on a fresh-tier hit
        boolean stale,                 // true when served from the stale tier
        Optional<Instant> staleSince   // present iff stale == true
    ) implements ProductOutcome { /* invariants enforced in compact constructor */ }

    record Unavailable(
        String productName,
        String reason,                 // timeout | upstream_5xx | auth_failure | no_data
        boolean stale                  // always false — product is omitted, not served
    ) implements ProductOutcome { /* invariants enforced in compact constructor */ }
}
```

Invariants (compact constructors): non-null required fields; defensive `List.copyOf` on
`integrations`; `Served` requires `staleSince.isPresent() == stale`; `Unavailable.stale` is
always `false`; `Unavailable.reason` non-blank.

**Key asymmetry for T09**: stale data is carried on `Served` (with `stale=true`), because a
stale serve still contributes integrations to the grid. `Unavailable` is the genuine omission
case. This matches RFC-001 §Supporting types: an `UnavailableProduct` entry with `stale:true`
references a product whose data IS present but stale; `stale:false` means omitted.

T09 derivations (not implemented here, documented for the contract):
- `metadata.cache_hit` = true iff every outcome is `Served` with `cacheHitPerProduct=true`.
- `metadata.as_of` = oldest `fetchedAt` across `Served` outcomes.

## Decision tree → outcome mapping

| Branch | Outcome | Cache write? |
|---|---|---|
| Fresh-tier hit | `Served(cacheHitPerProduct=true, stale=false, staleSince=empty)` | no |
| Fresh miss + adapter success, non-empty | `Served(cacheHitPerProduct=false, stale=false)` | yes (`writeOnSuccess`) |
| Fresh miss + adapter success, **empty** + no usable stale | `Unavailable(reason="no_data", stale=false)` | **no** |
| Fresh miss + adapter success, **empty** + stale-in-window | `Served(stale=true, staleSince=…)` | **no** |
| Fresh miss + failure/deadline + stale-in-window | `Served(stale=true, staleSince=…)` | no |
| Fresh miss + failure/deadline + no usable stale | `Unavailable(reason=<reasonCode or "timeout">, stale=false)` | no |

`reason` on the failure path is sourced from `AdapterException.reasonCode()` for thrown
exceptions, or synthesized as `"timeout"` for a total-deadline / per-adapter-timeout cutoff.
`no_data` is the only `reason` not originating from an exception.

Stale-fallback eligibility is gated on `AdapterException.isTransient()` (ADR-001 line 46:
"dispatch on `isTransient()` (stale-fallback eligibility)"). Transient failures
(`AdapterTimeoutException`, `AdapterUpstreamException`, and the synthesized total-deadline
timeout) read the stale tier and serve stale when a usable entry exists. A **permanent** failure
(`AdapterAuthException`, `isTransient()==false`) does **not** serve stale data — its committed
Javadoc says so explicitly, and RFC-001 §Stale-tier fallback step 1 scopes the fallback to
"timeout or upstream error." An auth failure therefore always produces
`Unavailable(reason="auth_failure")` regardless of stale-tier contents.

Empty-but-successful fetch is not an exception path: it reads the stale tier and serves stale
when present, else `Unavailable("no_data")` (work-plan literal). The empty success is never
cached.

## Concurrency, timeouts & failure isolation

Coordinator owns `Executors.newVirtualThreadPerTaskExecutor()` (one virtual thread per task).

1. For each adapter, `cache.readFresh(orgId, adapter.productName())`. **Fresh hit → no task
   submitted** (observably no adapter call) — collected directly as `Served(cacheHitPerProduct=true)`.
2. For each fresh miss, submit `executor.submit(() -> adapter.fetch(orgId, authHeaders))` →
   `Future<FetchResult>`, paired with its `productName`.
3. Compute the absolute deadline once: `deadline = start + totalDeadline`. Await each future
   with `future.get(remaining, MILLISECONDS)` where
   `remaining = min(perAdapterTimeout(productName), millisUntil(deadline))`, clamped at `>= 0`.
   This enforces per-adapter timeout AND total deadline in one wait. Absolute wall-clock
   deadline means a slow first adapter does not consume a fast second adapter's budget —
   futures that completed before the deadline return instantly regardless of await order.
4. Classify each future independently (its own try/catch — this is the isolation boundary):
   - normal non-empty `FetchResult` → `Served`; `cache.writeOnSuccess`.
   - normal empty `FetchResult` → stale-check → `Served(stale)` or `Unavailable("no_data")`; no write.
   - `TimeoutException` → `future.cancel(true)` (interrupt the virtual thread); synthesize
     transient timeout → stale-check → `Served(stale)` or `Unavailable("timeout")`.
   - `ExecutionException` → unwrap cause. If `AdapterException` → dispatch on `reasonCode()` →
     stale-check → `Served(stale)` or `Unavailable(reasonCode)`. Any other (unexpected) cause is
     not silently dropped: rethrow as an internal error so it reaches T09's `@ControllerAdvice`
     → 500 (an adapter-contract violation, not partial unavailability).
   - `InterruptedException` → restore the interrupt flag and treat as a timeout-class
     unavailability for that product; do not fail the whole request.

**Failure isolation**: one adapter's timeout or exception only ever produces that product's
`Unavailable` / stale `Served`; it never propagates out of `fetchAll`. No retry — single `fetch`
per adapter. Cache outages are already non-fatal (plan 01).

**Executor lifecycle**: created per call (a virtual-thread-per-task executor is cheap) inside a
try-with-resources (`AutoCloseable`, JDK 21+), so it is shut down deterministically when
`fetchAll` returns. Tasks for completed/timed-out futures are cancelled before close.

## Configuration

```java
@ConfigurationProperties("integration-registry.coordinator")
public record CoordinatorProperties(
    Duration totalDeadline,                  // default 20s
    Duration defaultPerAdapterTimeout,       // fallback when productName has no explicit entry; default 10s
    Map<String, Duration> perAdapterTimeout  // keyed by productName: {InsightConnect:5s, InsightIDR:15s}
) {
    // compact constructor: default nulls, validate all durations strictly positive,
    // copy the map defensively; perAdapterTimeout(productName) → entry-or-default accessor
}
```

`application.yaml` additions:

```yaml
spring:
  threads:
    virtual:
      enabled: true   # ADR-002: blocking RestClient adapters dispatched in parallel by the
                      # FanOutCoordinator require virtual threads to be truly concurrent.

integration-registry:
  coordinator:
    total-deadline: 20s
    default-per-adapter-timeout: 10s
    per-adapter-timeout:
      InsightConnect: 5s
      InsightIDR: 15s
```

Per-profile override points (local/staging/production) stay open in the existing profile
documents. Q5 numbers are these starting points; the SLO-tuning gate closes against staging
benchmarking and is non-blocking.

## Testing (TDD)

Synthetic in-process `IntegrationAdapter` doubles (fast-success, empty-success, each
exception-thrower, sleep-past-timeout). `IntegrationCache` mocked with Mockito. No
`MockRestServiceServer` (coordinator is adapter-agnostic), no new Testcontainers.

- **`FanOutCoordinatorTest`** (unit): fresh-hit-no-adapter-call
  (`verify(adapter, never()).fetch(...)`); each `reason` mapping (timeout, upstream_5xx,
  auth_failure, no_data); all stale-fallback branches; no-write-on-failure
  (`verify(cache, never()).writeOnSuccess` on failure/empty paths); write-on-success
  (`verify(cache).writeOnSuccess` on non-empty success); per-adapter timeout; total-deadline
  cutoff with a fast adapter preserved.
- **Concurrency test**: two adapters each sleeping ~300ms → assert total wall-time well under
  600ms (≈ slowest, not sum), proving virtual-thread parallelism is live. (Run within
  `FanOutCoordinatorTest` or a dedicated `FanOutCoordinatorConcurrencyTest`.)
- **`CoordinatorPropertiesBindingTest`**: defaults applied; per-adapter map binds; positive-
  duration validation; entry-or-default accessor.
- **`ProductOutcomeTest`**: `Served`/`Unavailable` invariants (`staleSince` present iff `stale`;
  `Unavailable.stale==false`; defensive copy of integrations).

`mvn verify` (JUnit + ArchUnit + PMD) stays green. The existing `CoordinatorMarker` /
`ControllerWithCoordinatorDependency` ArchUnit fixtures are test-only and remain valid —
do not remove them.

## Non-goals (held from the work plan)

No cache implementation (plan 01); no adapter implementations (T05/T06); no JSON envelope /
serialization / `metadata` computation (T09); no vendor grouping or health rollup (T08); no
retry; no inbound auth / 401 path (T10/T09); no bounded-dispatcher-pool rework (Q8 deferred —
unbounded one-task-per-adapter is a conscious MVP choice).

## Files

| File | Action |
|---|---|
| `coordinator/FanOutCoordinator.java` | new — `@Component`, the orchestration logic |
| `coordinator/ProductOutcome.java` | new — sealed interface + `Served`/`Unavailable` records |
| `coordinator/CoordinatorProperties.java` | new — `@ConfigurationProperties` |
| `coordinator/CoordinatorConfiguration.java` | new — `@Configuration @EnableConfigurationProperties(CoordinatorProperties.class)`, mirroring `CacheConfiguration` |
| `coordinator/package-info.java` | update the existing stub doc if needed |
| `src/main/resources/application.yaml` | add `spring.threads.virtual.enabled` + `integration-registry.coordinator.*` |
| `coordinator/FanOutCoordinatorTest.java` | new |
| `coordinator/CoordinatorPropertiesBindingTest.java` | new |
| `coordinator/ProductOutcomeTest.java` | new |

**Properties registration**: the repo registers `@ConfigurationProperties` beans via a
per-feature `@Configuration` with `@EnableConfigurationProperties` — `CacheConfiguration`
(`@EnableConfigurationProperties(CacheProperties.class)`), `VendorMappingConfiguration`, and
`InsightConnectClientConfig` all follow this; there is no global `@ConfigurationPropertiesScan`.
`CoordinatorProperties` follows the same pattern via a new `CoordinatorConfiguration`. The
ArchUnit `coordinator` rule permits this — Spring framework packages are not in the forbidden
set (`controller`/`service`/`aggregator`/`mapping`).
