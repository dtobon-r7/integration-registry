# Design — Adapter contract layer

**Work plan**: [`engagements/unified-integrations-view/project/tracks/05-adapter-interface-and-icon/work-plans/01-adapter-contract.md`](../../../../../engagements/unified-integrations-view/project/tracks/05-adapter-interface-and-icon/work-plans/01-adapter-contract.md)
**Track**: 05 — Adapter interface and InsightConnect adapter
**Branch**: `uiv/track-05/01-adapter-contract`
**Date**: 2026-05-26

---

## Outcome

Land the `IntegrationAdapter` seam — the interface, the `FetchResult` and `NormalizedIntegration` records, and the three adapter exception types — as the migration-safety boundary RFC-001 commits to. Types and shapes only; no behavior. Once shipped, plan 02 (ICON adapter), track 06 (IDR adapter), and track 07 (fan-out coordinator) can stagger-start against a stable contract.

## Architecture

A single Java package `com.rapid7.integrationregistry.adapter` exposes the entire seam:

- One interface — `IntegrationAdapter`
- Four value types — `FetchResult`, `NormalizedIntegration`, `SourceIdentifier`, `IntegrationStatus`
- Three exception types — `AdapterTimeoutException`, `AdapterAuthException`, `AdapterUpstreamException`

Nothing in this package depends on any other internal Registry layer; ArchUnit's `adapterLayer_shouldNotDependOnInternalLayers` rule already enforces this. The package depends only on the JDK and Spring's `org.springframework.http.HttpHeaders` (the `fetch` parameter, per RFC-001 §Adapter interface).

The seam is the migration-safety boundary RFC-001 commits to: replacing an HTTP-poller adapter with an event-consumer adapter in Phase 2 must not require touching the Registry core, the read API, the cache, or `VendorAggregator`. Everything that decides *how* to get integration data — HTTP clients, status mapping, fixtures, retries — lives on implementations of this interface, not in this package.

This PR ships *types and shapes only*. No HTTP, no mapping tables, no fixtures, no `@Component` annotations. Spring sees nothing in this PR.

## Components

All under `src/main/java/com/rapid7/integrationregistry/adapter/`.

### Interface — `IntegrationAdapter.java`

```java
public interface IntegrationAdapter {
    String productName();

    FetchResult fetch(String orgId, HttpHeaders authHeaders)
        throws AdapterTimeoutException,
               AdapterAuthException,
               AdapterUpstreamException;
}
```

`HttpHeaders` is `org.springframework.http.HttpHeaders`. The three exceptions are checked. No default methods, no nested types — implementations live in their own packages (or in `..adapter..` for ICON in plan 02).

### Records

All records use compact constructors with `Objects.requireNonNull(...)` guards on required fields. Nullable fields per RFC-001 §Normalized integration record carry no guard.

**Field-name constant convention.** Field names passed as the second argument to `Objects.requireNonNull` are not inlined as string literals — each declaring record exposes them as **package-private** `static final String FIELD_<NAME>` constants. Package-private (rather than `private`) lets the same-package test suite reference the same constants in null-rejection assertions, so a rename in the type cannot leave a stale-message test behind.

No shared `Constants.java`: the names are record-local (e.g., `FetchResult` owns `"integrations"`; nothing else does), and a shared file would imply lock-step versioning across types that share a name by coincidence. PMD's `AvoidDuplicateLiterals` is the backstop; this convention is the primary rule. The interface, enum, and exception types contain no string literals, so they need no constants.

**`FetchResult.java`**

```java
public record FetchResult(
    List<NormalizedIntegration> integrations,
    Instant fetchedAt
) {
    static final String FIELD_INTEGRATIONS = "integrations";
    static final String FIELD_FETCHED_AT = "fetchedAt";

    public FetchResult {
        Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
        Objects.requireNonNull(fetchedAt, FIELD_FETCHED_AT);
        integrations = List.copyOf(integrations);
    }
}
```

`List.copyOf` defensively copies the input and rejects null elements — both required because records are only shallow-immutable and the list is part of the contract.

**`SourceIdentifier.java`**

```java
public record SourceIdentifier(String sourceType, String sourceValue) {
    static final String FIELD_SOURCE_TYPE = "sourceType";
    static final String FIELD_SOURCE_VALUE = "sourceValue";

    public SourceIdentifier {
        Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
        Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
    }
}
```

The `(source_type, source_value)` pair carried inside `NormalizedIntegration`. RFC-001 §`source_type` enum draws `sourceType` from a closed set (`plugin_name`, `product_type`, `product_name`, `integration_id`); enforcement of the enum membership lives at the bundle-validation layer (T04), not here — adapters are trusted to use the right discriminator.

**`NormalizedIntegration.java`**

Nine fields per RFC-001 §Normalized integration record:

| Java field | Type | Required | Source field |
|---|---|---|---|
| `integrationId` | `String` | yes | `integration_id` |
| `sourceIdentifier` | `SourceIdentifier` | yes | `source_identifier` |
| `productName` | `String` | yes | `product_name` (must match `IntegrationAdapter.productName()`) |
| `integrationType` | `String` | yes | `integration_type` |
| `integrationLabel` | `String` | nullable | `integration_label` |
| `status` | `IntegrationStatus` | yes | `status` |
| `lastSuccessTimestamp` | `Instant` | nullable | `last_success_timestamp` |
| `configurationUrl` | `String` | yes | `configuration_url` |
| `customerAccountId` | `String` | yes | `customer_account_id` (internal-only; not surfaced on API responses) |

Compact constructor null-guards seven required fields; `integrationLabel` and `lastSuccessTimestamp` accept null. Field-name constants follow the convention above — seven `FIELD_<NAME>` constants, one per required field. Nullable fields carry no constant (never null-checked).

```java
public record NormalizedIntegration(
    String integrationId,
    SourceIdentifier sourceIdentifier,
    String productName,
    String integrationType,
    String integrationLabel,           // nullable
    IntegrationStatus status,
    Instant lastSuccessTimestamp,      // nullable
    String configurationUrl,
    String customerAccountId
) {
    static final String FIELD_INTEGRATION_ID = "integrationId";
    static final String FIELD_SOURCE_IDENTIFIER = "sourceIdentifier";
    static final String FIELD_PRODUCT_NAME = "productName";
    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_STATUS = "status";
    static final String FIELD_CONFIGURATION_URL = "configurationUrl";
    static final String FIELD_CUSTOMER_ACCOUNT_ID = "customerAccountId";

    public NormalizedIntegration {
        Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
        Objects.requireNonNull(sourceIdentifier, FIELD_SOURCE_IDENTIFIER);
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
        Objects.requireNonNull(customerAccountId, FIELD_CUSTOMER_ACCOUNT_ID);
    }
}
```

Outside the `com.rapid7.integrationregistry.adapter` package the constants are invisible — package-private access keeps them an internal contract between each record and its sibling test class.

`Instant` is the JVM-internal type. ISO 8601 wire serialization is T09's concern (read API edge).

### Enum — `IntegrationStatus.java`

```java
public enum IntegrationStatus {
    HEALTHY, WARNING, ERROR, MISSING_DATA, DISABLED;
}
```

No methods, no precedence ordering on the enum. Worst-state-wins precedence (RFC-001 §Status precedence rule, four-level rollup) lives on `VendorAggregator` (T08), not here.

### Exceptions

Three classes, each `extends Exception`, each with two constructors:

```java
public class AdapterTimeoutException extends Exception {
    public AdapterTimeoutException(String message) {
        super(message);
    }

    public AdapterTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`AdapterAuthException` and `AdapterUpstreamException` are identical in shape. **No common abstract parent** beyond `Exception` — adding one would defeat the work plan's independent-catchability acceptance signal and lose the future coordinator's ability to map each to a distinct `unavailable_products[].reason`.

The two-constructor surface lets adapters either throw with just a message (`throw new AdapterAuthException("401 from ICON")`) or wrap an underlying upstream exception while preserving the cause chain (`throw new AdapterUpstreamException("ICON 503", responseException)`).

## Data flow

This PR has no runtime data flow — only the contract for one. For context, the flow it enables (delivered later, not here):

```
VendorController
   ↓ (T07)
VendorService
   ↓ (T07)
FanOutCoordinator ──parallel──▶ IntegrationAdapter[]   ← this seam
   ↓                                  ↓ fetch(orgId, headers)
                            FetchResult { integrations[], fetchedAt }
                                       ↓
                                VendorAggregator (T08)
                                       ↓ resolves SourceIdentifier
                                       ↓ against VendorMappingSnapshot (T04)
                                       ↓
                              vendor / vendor_service / data_source
```

Two contract guarantees that protect this flow:

1. **Adapters never resolve vendor identity.** `NormalizedIntegration.sourceIdentifier` is the *raw* `(source_type, source_value)` from the upstream API. The aggregator (T08) is the single resolver. There is nowhere on `NormalizedIntegration` for adapter authors to populate vendor fields — that's the design.

2. **Adapters never enforce timing.** The interface declares the three failure modes, but the per-adapter timeout is set and enforced by the coordinator's `@Async` wrapper (T07). Adapter implementations may use their own internal HTTP timeouts (e.g., `WebClient` connect/read), but the *coordinator's* deadline is the contract one.

## Error handling

| Exception | Adapter throws when | Future coordinator maps to |
|---|---|---|
| `AdapterTimeoutException` | The adapter's own HTTP/operation timeout fires | `unavailable_products[].reason = "timeout"` |
| `AdapterAuthException` | Upstream returns 4xx (401/403 specifically; other 4xx is adapter-author choice) | `unavailable_products[].reason = "auth_failure"` |
| `AdapterUpstreamException` | Upstream returns 5xx, or a transport error the adapter classifies as "upstream broken" | `unavailable_products[].reason = "upstream_5xx"` |

Constructors:
- `(String message)` — `super(message)`. Adapter-internal cases without an underlying cause.
- `(String message, Throwable cause)` — `super(message, cause)`. Wraps an upstream exception (e.g., `SocketTimeoutException`, `WebClientResponseException`) while preserving the cause chain.

What this contract deliberately does **not** specify:

- **No retry semantics.** RFC-001 specifies single attempt per adapter per request; the coordinator owns that. Nothing in this seam encodes it.
- **No HTTP-status-to-exception mapping.** RFC's classification (4xx → `auth_failure`, 5xx → `upstream_5xx`) lives in *each adapter's* implementation. The interface only asserts: *if* you fail with auth, throw `AdapterAuthException`. How you decide is the adapter's problem.
- **No partial-success encoding.** `FetchResult` cannot carry "got 8 of 10 connections, the other 2 failed mid-stream." If part of a fetch fails, the adapter throws (the coordinator records the whole product as unavailable) or it returns `FetchResult` with what it has and logs the rest. RFC silent on the latter case; punt to plan 02 for ICON-specific behavior.

This shape makes the migration-safety guarantee real: a Phase 2 event-consumer adapter that has no concept of "HTTP timeout" still throws `AdapterUpstreamException` for "Kafka topic unavailable", and the coordinator's `unavailable_products[].reason` mapping just works.

## Testing

Five test classes in `src/test/java/com/rapid7/integrationregistry/adapter/`. JUnit 5 only, AAA-structured, `methodName_shouldDoX_whenY()` naming per [TESTING.md](../../../TESTING.md). No Spring context, no fixtures, no contract tests — all of those land in plan 02.

Null-rejection assertions reference the package-private `FIELD_<NAME>` constants on the record under test (e.g., `assertThat(thrown.getMessage()).isEqualTo(FetchResult.FIELD_INTEGRATIONS)`), so a field rename and the test stay locked together.

### `SourceIdentifierTest.java`

- `constructor_shouldBuildRecord_whenBothFieldsProvided`
- `constructor_shouldThrowNPE_whenSourceTypeNull`
- `constructor_shouldThrowNPE_whenSourceValueNull`

### `IntegrationStatusTest.java`

- `values_shouldContainExactlyFiveStates_whenInspected` — regression guard if a sixth state is added without RFC amendment
- `valueOf_shouldResolveAllFiveConstants_whenLookedUpByName`

### `NormalizedIntegrationTest.java`

- One happy-path test with all nine fields populated.
- Seven null-rejection tests, one per required field: `integrationId`, `sourceIdentifier`, `productName`, `integrationType`, `status`, `configurationUrl`, `customerAccountId`.
- One nullable-acceptance test for `integrationLabel`.
- One nullable-acceptance test for `lastSuccessTimestamp`.

### `FetchResultTest.java`

- `constructor_shouldBuildRecord_whenIntegrationsListAndTimestampProvided`
- `constructor_shouldAcceptEmptyList_whenNoIntegrationsReturned`
- `constructor_shouldThrowNPE_whenIntegrationsListNull`
- `constructor_shouldThrowNPE_whenFetchedAtNull`
- `integrations_shouldBeImmutable_whenSourceListMutatedAfterConstruction` — caller passes a mutable list, mutates it after construction, asserts the record's view is unchanged. Proves `List.copyOf` is doing its job.

### `AdapterExceptionsTest.java`

- Three message-only-ctor tests (one per exception), each asserting the message round-trips through `getMessage()`.
- Three message-and-cause-ctor tests, each asserting both `getMessage()` and `getCause()` round-trip.
- `independentlyCatchable_shouldDistinguishEachType_whenThrownInSeparateBlocks` — three separate try/catch blocks, each catching exactly one type, proving they don't share a parent other than `Exception`. Without this test a future refactor could introduce an `AdapterException` parent and silently break the coordinator's `unavailable_products[].reason` fidelity.

### Build gate

`./mvnw verify` stays green:
- JUnit (the new tests above)
- ArchUnit (`adapterLayer_shouldNotDependOnInternalLayers` and the rest of `LayerDependencyRules` continue to pass)
- PMD (curated ruleset — empty catches, unused code, structural bloat, placeholders — applies to both main and test sources)

## Non-goals

- **No adapter implementation.** ICON belongs to plan 02; IDR to track 06.
- **No status-derivation logic, mapping tables, or fixtures.** Plan 02 owns ICON status mapping.
- **No fan-out, cache, retry, or timeout enforcement.** Track 07.
- **No vendor-name resolution.** T08 against the T04 snapshot.
- **No outbound Class 3 header attachment.** T10 + plan 02.
- **No KB doc updates.** Track 05 scope notes 21.02 is already aligned.
- **No JSON serialization annotations.** No `@JsonProperty`, no Jackson configuration. Wire-form mapping is T09's job (read API edge).
- **No Spring annotations.** No `@Component`, no `@Service`. Implementations may be Spring-managed; the contract types are not.

## Acceptance signals

- The interface signature, `FetchResult` field set, `NormalizedIntegration` field set, and the three exception types match RFC-001 §Adapter interface and §Normalized integration record exactly — names, types, nullability.
- `NormalizedIntegration` exposes the raw source identifier as a `SourceIdentifier(sourceType, sourceValue)` pair — not a single resolved vendor identifier.
- `integrationLabel` and `lastSuccessTimestamp` are nullable; `customerAccountId` is present on the record (RFC marks it required-internal-only, not surfaced on API responses; the contract here is "always populated by adapters").
- Each of the three adapter exceptions is independently catchable.
- ArchUnit rules from T03 still pass — new types respect package layer boundaries.
- `./mvnw verify` is green: JUnit + ArchUnit + PMD.

## References

- `engagements/unified-integrations-view/decisions/rfc/RFC-001-integration-registry.md`
  - §Adapter interface (lines 582–609)
  - §Normalized integration record (lines 766–783)
  - §Canonical `productName()` values (lines 786–806)
  - §`source_type` enum (lines 810–828)
  - §Status precedence rule (lines 832–848) — for context; not implemented here
- `engagements/unified-integrations-view/docs/20-29-technical-design/21-architecture/21.02-reference-integration-schema.md`
- `engagements/unified-integrations-view/project/tracks/05-adapter-interface-and-icon/scope.md`
- `repos/platform/integration-registry/TESTING.md`
- `repos/platform/integration-registry/src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
