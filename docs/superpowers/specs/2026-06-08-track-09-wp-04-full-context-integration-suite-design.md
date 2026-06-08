# WP-04 — Full-context read-path integration suite — Design

**Date**: 2026-06-08
**Work plan**: `engagements/unified-integrations-view/project/tracks/09-read-api-and-orchestration/work-plans/04-full-context-integration-suite.md`
**Mode**: Fresh
**Branch / worktree**: `worktree-track-09-wp-04`

## Outcome

An integration test suite boots the full Spring context — `FanOutCoordinator` +
`VendorAggregator` + `VendorService` + `VendorController` wired together — with
only the adapters faked, and proves the six read-path scenarios from the track
exit criteria end-to-end behind the HTTP edge.

## Decisions locked at the ambiguity gate

1. **Real Valkey via Testcontainers** (developer override; aligns with ADR-006 +
   TESTING.md). The suite extends the existing `ValkeyTestContainer` base. The work
   plan's line-21 "(no TestContainers)" parenthetical predated ADR-006 and is
   superseded.
2. **Adapters faked with Mockito stub beans** — two products (`InsightIDR`,
   `InsightConnect`). The real `InsightConnectAdapter` is replaced/neutralized so it
   is not part of the coordinator's autowired `Set<IntegrationAdapter>`; WireMock and
   real-adapter HTTP parsing are out of scope (adapter-contract-test territory).
3. **One shared purpose-built multi-service test bundle** — a single `@SpringBootTest`
   context (boots once). Microsoft carries 2+ vendor services for the vendor-scoped
   scenario; per-scenario variation comes from cache seeding + adapter stubbing, never
   from swapping the bundle.
4. **Cache isolation by flush + seed** — `@BeforeEach` flushes Valkey; each test seeds
   the tiers it needs via the autowired real `IntegrationCache.writeOnSuccess(...)` and
   `StringRedisTemplate` (delete the fresh key for stale-only), mirroring
   `IntegrationCacheValkeyTest`.
5. **Assertion style** — deserialize the HTTP body into the Plan-01 response DTOs and
   assert typed fields with AssertJ; JsonPath only for occasional shape spot-checks.
   (DTO serialization pinning against `openapi.json` is Plan 01's job — a non-goal here.)
6. **T10 is not a blocker** — this adapter-faking suite explicitly excludes the
   401/filter-chain path. The genuine "401-before-handler" assertion stays deferred to
   T10.

## Architecture

```
RestClient ──HTTP──▶ VendorController ─▶ VendorService ─▶ FanOutCoordinator ─▶ [stub adapters]
  (test)            (real)             (real)           (real)             │
                                         └─▶ VendorAggregator (real)        └─▶ IntegrationCache ─▶ real Valkey
                                               └─▶ VendorMappingSnapshot (real, from seeded test bundle)
```

- **Real**: controller, service, coordinator, aggregator, cache, mapping snapshot, Valkey.
- **Faked**: the two `IntegrationAdapter` beans (Mockito stubs); `S3Client`
  (`@MockitoBean`) — the bundle is staged on disk so the loader reads it without S3.

### Boot wiring

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadPathIntegrationTest extends ValkeyTestContainer {

  @TestConfiguration
  static class StubAdapters {
    // Defines exactly the two stub IntegrationAdapter beans the coordinator autowires.
  }

  @MockitoBean S3Client s3Client;          // bundle staged on disk, not fetched from S3
  @Autowired IntegrationCache cache;        // real cache, for seeding tiers
  @Autowired StringRedisTemplate redis;     // for fresh-key deletion + flush
  @Autowired IntegrationAdapter insightIdr; // stub, reconfigured per scenario
  @Autowired IntegrationAdapter insightConnect; // stub
  @LocalServerPort int port;

  @BeforeAll static void seedBundle()       // write multi-service-test.tgz to temp cache-dir
  @DynamicPropertySource static void props() // bundle-version, s3-*, cache-dir, icon base-url/icon-base
  @BeforeEach void flushCache()              // FLUSHALL via redis connection + reset(stubs)
}
```

### Stub-adapter mechanism (RESOLVED — verified against the code)

`FanOutCoordinator.validateProductNames` runs **in the constructor at context
startup** (`FanOutCoordinator.java:68`) and throws on a null/blank `productName()`.
A Mockito mock's `productName()` returns `null` at construction and cannot be stubbed
before the context wires the coordinator — so **`@MockitoBean IntegrationAdapter` /
`@MockitoBean InsightConnectAdapter` would fail boot**. Disqualified.

**Decision:** use **hand-written stub adapter classes** with a hard-coded
`productName()` (the `CoordinatorAdapterFixtures.CountingAdapter` pattern,
`CoordinatorAdapterFixtures.java:45-65`) and a per-test-settable `fetch()`:

```java
static final class StubAdapter implements IntegrationAdapter {
  private final String productName;                       // hard-coded → survives boot validation
  volatile BiFunction<String, HttpHeaders, FetchResult> behavior; // per-test settable
  // throwing variant: behavior that throws the AdapterException the scenario needs
  @Override public String productName() { return productName; }
  @Override public FetchResult fetch(String orgId, HttpHeaders h) { return behavior.apply(orgId, h); }
}

@TestConfiguration
static class StubAdapters {
  @Bean StubAdapter insightConnectAdapter() { return new StubAdapter("InsightConnect"); } // name evicts real @Component
  @Bean StubAdapter insightIdrAdapter()     { return new StubAdapter("InsightIDR"); }
}
```

**Evicting the real `InsightConnectAdapter @Component`:** the ICON stub `@Bean` method
is named `insightConnectAdapter` (collides with the scanned component's default bean
name) and the test sets **`spring.main.allow-bean-definition-overriding=true`** via
`@DynamicPropertySource`, making the override deterministic (not environment-sensitive).
Result: the coordinator's `Set<IntegrationAdapter>` holds exactly
`{InsightConnect-stub, InsightIDR-stub}`.

**De-risk first (walking skeleton):** the first implementation task is a single boot
test asserting the context starts and the coordinator sees exactly those two
`productName()`s. If bean-name override proves unreliable in this context, the
fallback is a component-scan exclude-filter on `InsightConnectAdapter` — but the
override-with-flag path is the primary and is proven by task 1 before any scenario is
written.

**`InsightConnectClientConfig` still boots** even with the adapter replaced (it is
component-scanned independently and eagerly builds `insightConnectRestClient`), so
`integration-registry.insightconnect.base-url` and `.icon-base` remain **required**
`@DynamicPropertySource` entries (`InsightConnectProperties.java:28-29`), exactly as in
`IntegrationRegistryApplicationTests`.

## Test bundle (`src/test/resources/vendor-mapping/bundle/multi-service-test.yaml`)

```
apiVersion: registry.rapid7.com/v1
kind: VendorMapping
metadata: { mapping_version: v1.0.0-test }
spec:
  vendors:
    - microsoft (Microsoft):
        microsoft-defender (edr):
          - InsightIDR     / product_type / microsoft-defender-endpoint
          - InsightConnect / plugin_name  / microsoft-defender
        microsoft-sentinel (siem):
          - InsightIDR     / product_type / microsoft-sentinel
          - InsightConnect / plugin_name  / microsoft-sentinel
    - atlassian (Atlassian):
        jira (itsm):
          - InsightConnect / plugin_name  / jira
```

The stub adapters emit `NormalizedIntegration`s whose `(productName, sourceType,
sourceValue)` triplets match these data sources, so the aggregator resolves them to
the seeded vendor services. `mapping_version: v1.0.0-test` lets scenarios assert
`metadata.mapping_version` deterministically.

## Scenario → driver matrix

Each test flushes Valkey in `@BeforeEach`, then seeds tiers + stubs adapters, calls
the route, and asserts the composed outcome.

| # | Scenario | Cache seed | Adapter stubs | Key assertions |
|---|---|---|---|---|
| 1 | Cache-hit happy path | fresh tier seeded for both | `fetch()` **never** called (verify never) | `cache_hit:true`; payload from cached data; `as_of` = cached `fetched_at` |
| 2 | Cache-miss | empty | both return fresh data | `cache_hit:false`; payload correct; both `fetch()` invoked |
| 3 | Partial + stale fallback | stale-only for IDR (write both tiers, delete fresh key); ICON empty | IDR throws `AdapterUpstreamException` (5xx, transient); ICON returns fresh | IDR in `unavailable_products[]` with `stale:true` + populated `stale_since`; `as_of` = oldest contributing `fetched_at` |
| 4 | Partial + omission | empty | IDR throws `AdapterAuthException` (permanent); ICON returns data | IDR omitted from payload, in `unavailable_products[]` with `stale:false` + `reason: auth_failure`; ICON data present |
| 5 | All-adapter-failure | empty | both throw, no stale | **200**; empty top-level array; one `unavailable_products[]` entry per failed product |
| 6 | Vendor-scoped projection | empty | both return data spanning both Microsoft services | `GET /vendors/microsoft` → nested vendor-service projection with 2 services; vendor-level `aggregate_health` + `last_updated` rolled up across services |

Reason→behavior follows `OutcomeClassifier`: transient (`timeout`, `upstream_5xx`)
→ serve-stale-or-omit; permanent (`auth_failure`) → omit outright (never reads stale).

## File layout

- `src/test/java/com/rapid7/integrationregistry/integration/ReadPathIntegrationTest.java`
  — the suite (may split per concern if it grows past one focused class).
- `StubAdapters` `@TestConfiguration` (nested or standalone) — the two stub beans.
- `ReadPathFixtures` — builders for `NormalizedIntegration` / `FetchResult` matching
  the test bundle's triplets.
- `src/test/resources/vendor-mapping/bundle/multi-service-test.yaml` — the bundle.

## Testing approach

This IS the test deliverable; there is no separate test-of-tests. Verification:
`./mvnw verify` (Docker required for Valkey) runs the suite green, and the six
scenarios each assert their documented outcome. ArchUnit excludes test sources;
PMD applies to tests, so the suite must pass PMD.

## Non-goals (from the work plan)

- Live ICON/IDR backends; real-adapter HTTP parsing (adapter-contract-test territory).
- Re-testing T07/T08 internals (timeout mechanics, rollup precedence, `data_source_id`
  minting).
- Controller-only slice coverage / the 401 path (Plan 03 + T10).
- DTO serialization pinning against `openapi.json` (Plan 01).
- Performance / load / concurrency-stress testing.
