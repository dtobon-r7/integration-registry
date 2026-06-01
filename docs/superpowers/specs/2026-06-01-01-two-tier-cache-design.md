# Design — Two-tier Valkey cache and cache-key abstraction

**Work plan**: `engagements/unified-integrations-view/project/tracks/07-fan-out-and-cache/work-plans/01-two-tier-cache.md`
**Track**: 07 — Fan-out coordinator and two-tier cache
**Date**: 2026-06-01
**Status**: approved

---

## Outcome

A self-contained two-tier cache component for the Integration Registry, backed by
**Valkey** (per **ADR-005** — `engagements/unified-integrations-view/decisions/adr/ADR-005-use-valkey-for-registry-cache.md`
in the x-team-tools repo — superseding RFC-001 §Cache layer's in-process Caffeine choice). Two logically
independent tiers — **fresh** (within a short TTL, served as a normal hit) and **stale**
(past fresh TTL but within a long retention window, served only as a failure fallback) —
with independently configurable TTLs, a write-on-success-only / never-overwrite-good-stale
invariant, and a single-call-site cache-key function. This plan ships the cache and its
key function only; the `FanOutCoordinator` that decides *when* to read fresh, fall back to
stale, or write is track 07 plan 02.

## Binding decisions (from execute-plan ambiguity gate)

- **Backing store: Valkey**, not Caffeine (ADR-005). Shared across replicas — removes the
  cross-replica flicker RFC-001 §Cross-replica consistency accepted as an in-process trade-off.
- **Package: a new top-level layer** `com.rapid7.integrationregistry.cache` — the package
  the coordinator depends on.
- **Approach: direct `RedisTemplate`** with the two tiers as key-prefixed namespaces in one
  Valkey instance, each with its own per-key TTL. (Spring `@Cacheable` rejected — it cannot
  express conditional caller-driven writes or two distinct read semantics.)
- **Client + serde: Spring Data Redis + Lettuce**, `FetchResult` as Jackson JSON inside a
  versioned envelope.
- **Tests: Testcontainers `valkey/valkey:8-alpine`** for integration coverage, plus
  no-container unit tests for the codec and key contract. This reverses `TESTING.md`'s
  Docker-free rule for the cache tests only — captured in **ADR-006**; `TESTING.md` and the
  repo `CLAUDE.md` updated accordingly. Accepted consequence: `./mvnw verify` now requires Docker.

## Architecture & package layout

New top-level layer:

```
com.rapid7.integrationregistry.cache/
├─ package-info.java        # layer doc (mirrors coordinator/package-info.java style)
├─ IntegrationCache.java    # the component — the only type the coordinator calls
├─ CacheKey.java            # single-call-site key builder: (tier, orgId, productName) → key string
├─ CacheTier.java           # enum { FRESH, STALE }
├─ StaleEntry.java          # record: FetchResult + staleSince (the stale-read return type)
├─ FetchResultCodec.java    # FetchResult ↔ versioned JSON; unreadable/old version → empty (miss)
├─ CacheProperties.java     # @ConfigurationProperties("integration-registry.cache")
└─ ValkeyCacheConfig.java   # @Configuration: LettuceConnectionFactory + RedisTemplate<String,String>
```

**Dependency direction:** `coordinator → cache → adapter` (for `FetchResult`). The `cache`
layer may depend on Spring Data Redis / Lettuce and Jackson; it imports nothing from
`controller / service / aggregator / coordinator / mapping`.

**New ArchUnit rule** (added to `LayerDependencyRules`):

```java
static final ArchRule cacheLayer_shouldNotDependOnDisallowedLayers =
    noClasses().that().resideInAPackage("..cache..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..controller..", "..service..", "..aggregator..",
                            "..coordinator..", "..mapping..");
```

This is additive to the existing rule set. It is the one structural decision that falls out
of introducing a new layer (flag for Phase 7 autonomy review).

## Component surface

```java
public class IntegrationCache {

  /** Fresh-tier read: a FetchResult within fresh TTL, or empty. Never returns a stale entry. */
  Optional<FetchResult> readFresh(String orgId, String productName);

  /** Stale-tier read: a distinct operation the coordinator's failure path calls. */
  Optional<StaleEntry> readStale(String orgId, String productName);

  /** Write-on-success: populate fresh AND refresh stale for this key. The only write path. */
  void writeOnSuccess(String orgId, String productName, FetchResult result);
}
```

- `readFresh` returns a bare `FetchResult`; `readStale` returns a `StaleEntry` (FetchResult +
  `staleSince`) because the coordinator needs the extra signal to set `stale: true` /
  `stale_since` downstream.
- **The no-overwrite invariant is structural, not defensive.** There is exactly one write
  method, and it is only ever called on a successful fetch. A failed fetch never reaches
  `writeOnSuccess`, so a good stale entry is untouched *by construction* — there is no
  check-then-skip branch that could regress.
- **All reads are total: they never throw.** Any Valkey error or unreadable payload yields
  `Optional.empty()` (a miss). This is the contract plan 02 builds its non-fatal-cache-outage
  fallback on.

```java
public record StaleEntry(FetchResult result, Instant staleSince) {}
```

## Keys

`CacheKey` is the **single Valkey-key construction site**:

```java
static String of(CacheTier tier, String orgId, String productName)
//  → "ir:cache:{tier}:{orgId}:{productName}"
//    e.g. "ir:cache:fresh:org-123:InsightConnect"
```

- `ir:cache:` namespaces the Registry's keys within a shared Valkey.
- `{tier}` (`fresh` / `stale`) yields two independent keyspaces: distinct keys ⇒ independent
  per-key expiry ⇒ stale expiry never affects a fresh key and vice-versa. **Tier independence
  is guaranteed by construction**, not by a separate eviction policy.
- Extending the key to `(orgId, userId, productName)` touches only this method's signature and
  its direct callers — verifiable by this being the sole key-building site.

**Assumption (recorded):** `orgId` and `productName` are platform-controlled values
(`productName` is the RFC-canonical frozen string set; `orgId` is a platform org identifier)
and do not contain the `:` delimiter. This plan does not sanitize/encode them; if a future
key dimension can contain `:`, the encoding lives in this one function.

## TTLs & configuration

`CacheProperties` bound from `integration-registry.cache`:

```yaml
integration-registry:
  cache:
    fresh-ttl: 5m        # RFC starting point; configurable per environment
    stale-ttl: 24h       # RFC starting point; configurable per environment
    valkey:
      host: ${...}       # from env per environment (no default — absent fails fast at binding)
      port: 6379
      command-timeout: 250ms   # small read-path budget; a slow Valkey degrades to a miss
```

- Each TTL is applied as Valkey per-key expiry at write time (`SET key value EX ttl`).
  Independently configurable; a per-environment override of one tier does not affect the other.
- TTL defaults (`5m` / `24h`) live in `application.yaml`. Connection (`host`) comes from the
  environment with no default, matching the existing `VendorMappingProperties` "absent config
  fails fast at binding" convention.
- `command-timeout: 250ms` is a deliberate small budget so a degraded Valkey fails fast to a
  miss rather than consuming the fan-out request deadline. Configurable per environment.

## Serialization — `FetchResultCodec`

- `FetchResult` → JSON string via a dedicated Jackson `ObjectMapper` (records + `Instant` via
  `JavaTimeModule`, ISO-8601).
- Payload wrapped in a **versioned envelope**: `{"v":1,"payload":{ ...FetchResult... }}`.
- On read: an unknown/missing `v` or any deserialization failure → `Optional.empty()` (a miss),
  logged at debug. This realizes "incompatible payload = miss, never an exception" and makes a
  future `NormalizedIntegration` schema change safe — old entries are silently ignored, not fatal.
- The codec is the only place in the layer that touches JSON; everything else works in domain
  types or `String` Valkey values.

## Data flow

**Write (`writeOnSuccess`):**

1. Coordinator obtains a successful `FetchResult`.
2. `codec.encode(result)` → versioned JSON string.
3. `SET ir:cache:fresh:{org}:{product}` = json, `EX fresh-ttl`.
4. `SET ir:cache:stale:{org}:{product}` = json, `EX stale-ttl`.
5. Any Valkey failure → **log at warn, swallow.** A cache-write failure must never fail an
   otherwise-successful fetch. Returns void regardless.

**Read (`readFresh` / `readStale`):**

- `readFresh`: `GET fresh:{key}` → if present, `codec.decode` → `Optional<FetchResult>`
  (decode failure → empty). Absent or Valkey error → empty.
- `readStale`: `GET stale:{key}` → `codec.decode` → `StaleEntry(result, result.fetchedAt())`.
  Decode failure / absent / error → empty.

**`stale_since` = `FetchResult.fetchedAt()`** (not a separate cache-write timestamp). Rationale:
the RFC's `stale_since` answers "how old is this data," and `fetchedAt` is exactly the moment
the data was fetched from the product. A separate cache-write time would drift from the real
data age on every refresh. (Design decision — flag for Phase 7.)

## Error handling

| Failure | Read path | Write path |
|---|---|---|
| Valkey unreachable / command timeout | `Optional.empty()` (miss), debug log | swallow, warn log |
| Payload unreadable / unknown version | `Optional.empty()` (miss), debug log | n/a |
| Guarantee | reads never throw | writes never throw |

## Testing strategy

**Integration — Testcontainers `valkey/valkey:8-alpine`** (per ADR-006, which amends
`TESTING.md`'s Docker-free rule for the cache tests only):

- Tier independence: write both tiers with short TTLs; after fresh expires, `readFresh` is
  empty and `readStale` still returns the entry; fresh-key expiry leaves the stale key intact.
- Configurable-TTL boundaries: an entry is readable before its tier TTL and gone after.
- Write-on-success populates both tiers for the key.
- **No-overwrite invariant:** write a good entry; simulate "no successful fetch" (do not call
  `writeOnSuccess`); assert the stale value and its `staleSince` are identical before and after.
- Serialization round-trip: a `FetchResult` (with integrations + `fetchedAt`) survives
  encode→Valkey→decode unchanged.
- Incompatible-payload-as-miss: write a malformed / wrong-version value directly to a key;
  assert the read returns empty and does not throw.

**Unit (no container):**

- `FetchResultCodec` round-trip and version-mismatch → empty.
- `CacheKey` single-call-site contract: a focused test (and/or source assertion) that key
  construction happens in exactly one place and a `(org, user, product)` change is local.

**ArchUnit + build:**

- New `cache` layer rule passes; nothing outside allowed layers imports `cache`.
- `./mvnw verify` green: JUnit, ArchUnit, PMD, Spotless. **Requires Docker** (Testcontainers).

## New dependencies (pom.xml)

- `org.springframework.boot:spring-boot-starter-data-redis` (Lettuce client, Valkey-compatible).
- `org.testcontainers:testcontainers` + `org.testcontainers:junit-jupiter` (test scope).
  Pin versions consistent with the repo's existing explicit-version convention.

## Non-goals (carried from the work plan)

- No coordinator / dispatch / fallback **decision** logic (plan 02).
- No cache-**outage policy** — the surface fails observably-as-a-miss; *deciding* "Valkey down →
  proceed from adapters" is plan 02.
- No Valkey provisioning / deployment topology (infra).
- No `unavailable_products` / `metadata` shaping (plan 02 → T09).
- No user-scoped keying **implementation** — the abstraction permits `(org, user, product)`;
  MVP keys `(org, product)`.
- No adapter invocation or HTTP.

## Acceptance signals (from the work plan)

- Fresh and stale tiers independent (stale read returns expired-from-fresh entry; fresh read
  does not; stale expiry leaves fresh untouched).
- Each tier's TTL independently configurable; defaults fresh 5m / stale 24h; one override does
  not affect the other — verified against real Valkey per-key expiry.
- Successful-fetch write populates fresh and refreshes stale.
- No successful fetch → existing good stale entry observably unchanged (value + `staleSince`).
- `FetchResult` round-trips through Valkey serialization without loss; unreadable / incompatible
  stored payload surfaces as a miss, never an escaping exception.
- Cache key produced by exactly one function; `(org, user, product)` change touches only it.
- ArchUnit rules pass (incl. the new `cache` rule); `./mvnw verify` stays green.
