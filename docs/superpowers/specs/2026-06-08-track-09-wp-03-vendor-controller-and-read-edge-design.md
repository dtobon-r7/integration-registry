# Design — Track 09 / WP 03: VendorController and the read-path HTTP edge

**Date**: 2026-06-08
**Work plan**: `engagements/unified-integrations-view/project/tracks/09-read-api-and-orchestration/work-plans/03-vendor-controller-and-read-edge.md`
**Mode**: Fresh execution (execute-plan)

## Outcome

`VendorController` exposes the four locked read routes under `/integration-registry/v1/`,
mapping `VendorService` results to HTTP responses and the error envelope. It is the thin
HTTP boundary RFC-001 §Spring layer boundaries restricts to "HTTP only: parse request,
extract headers, serialize responses; no cache, no fan-out, no business logic." It mounts
behind the inbound identity filter chain T10 *will* enforce, so it never returns 401 itself.

## Context grounding (verified against code, not memory)

- **wp-01 (#13, merged)** — all wire DTOs exist: `VendorServicesResponse`,
  `VendorServiceDetailResponse`, `VendorsResponse`, `VendorDetailResponse`,
  `ErrorEnvelopeDto` (`{"error":{"code","message"}}`), `ErrorCode`
  (`UNAUTHENTICATED|NOT_FOUND|INTERNAL`). All use `@JsonNaming(SnakeCaseStrategy)` under
  Jackson 3 (`tools.jackson`); enums carry the legacy `com.fasterxml @JsonValue` (proven
  load-bearing by `DtoEnumTest`).
- **wp-02 (#14, merged)** — `VendorService` exposes exactly four methods:
  - `VendorServicesResponse listVendorServices(String orgId, OutboundAuth auth)`
  - `VendorsResponse listVendors(String orgId, OutboundAuth auth)`
  - `Optional<VendorServiceDetailResponse> getVendorServiceDetail(String orgId, String vendorServiceId, OutboundAuth auth)`
  - `Optional<VendorDetailResponse> getVendorDetail(String orgId, String vendorId, OutboundAuth auth)`
  - The two detail methods return `Optional.empty()` **only** for the true-not-found case
    (fresh AND stale confirm emptiness) and a present empty-payload response for
    partial-unavailability. The 404-vs-partial *decision* is entirely the service's.
- **`OutboundAuth`** (`..auth..`) — framework-neutral record wrapping
  `Map<String,String>`; `OutboundAuth.of(Map)` / `OutboundAuth.empty()`. The controller
  builds it from inbound identity headers to forward downstream.
- **T10 gap (accepted risk)** — the inbound identity filter chain (JWT validation,
  `X-IPIMS-*` extraction, 401, `/actuator/health` exempt) is **not built/merged**: no
  `spring-boot-starter-security`, no `SecurityFilterChain`, no `X-IPIMS` handling in
  `src/main`. The controller code has **zero** dependency on T10; only the
  "401-enforced-by-filter-chain" acceptance signal does.
- **No `server.servlet.context-path`** in `application.yaml` — the controller carries the
  full `/integration-registry/v1` prefix itself. (openapi `servers.url` is a Kong-mount
  doc prefix, not a Spring context-path.)
- **ArchUnit** `controllerLayer_shouldNotDependOnInternalLayers` forbids `..controller..`
  depending on `..coordinator..`, `..adapter..`, `..aggregator..`, `..mapping..`. Allowed:
  `..service..`, `..controller.dto..`, `..auth..`.

## Components

### 1. `VendorController` (`@RestController`)

Class-level `@RequestMapping("/integration-registry/v1")`. Constructor-injected
`VendorService`. Four handlers:

| Method + path | Service call | Success | Not-found |
|---|---|---|---|
| `GET /vendor-services` | `listVendorServices(orgId, auth)` | 200 body | — |
| `GET /vendor-services/{vendor_service_id}` | `getVendorServiceDetail(...)` | 200 body | `Optional.empty()` → 404 |
| `GET /vendors` | `listVendors(orgId, auth)` | 200 body | — |
| `GET /vendors/{vendor_id}` | `getVendorDetail(...)` | 200 body | `Optional.empty()` → 404 |

Each handler:
- `@RequestHeader("X-IPIMS-ORG-ID") String orgId` and
  `@RequestHeader("X-IPIMS-USER-ID") String userId` — both `required=true` (default).
  Missing → Spring's natural **400** (`MissingRequestHeaderException`). This is *not* 401
  and *not* the controller's auth logic; it's a defensive transport fallback only reachable
  on a direct call that bypasses T10. In production T10 guarantees presence.
- Builds `OutboundAuth.of(Map.of("X-IPIMS-ORG-ID", orgId, "X-IPIMS-USER-ID", userId))` to
  forward identity downstream.
- Path params bind the exact snake_case names from openapi (`vendor_service_id`,
  `vendor_id`); type `String` (Slug pattern is the contract's, not re-validated here — no
  business logic in the controller).
- Detail handlers: `return service.getX(...).orElseThrow(() -> new ResourceNotFoundException(message))`.
- Return the DTO directly (Spring serializes to 200). No `ResponseEntity` needed for the
  happy path; the 200-with-empty-payload-and-populated-`unavailable_products[]` case is just
  a present DTO returned normally — the controller is oblivious to its emptiness.

### 2. `ReadApiExceptionHandler` (`@RestControllerAdvice`)

The single centralized error-envelope emitter for the read path. Two handlers:

- `@ExceptionHandler(ResourceNotFoundException.class)` →
  `ResponseEntity.status(404).body(envelope(NOT_FOUND, ex.getMessage()))`.
- `@ExceptionHandler(RuntimeException.class)` →
  `ResponseEntity.status(500).body(envelope(INTERNAL, "Internal error"))`.
  - **Load-bearing**: the catch-all is `RuntimeException`, NOT `Exception`. Spring's
    `MissingRequestHeaderException` extends `ServletException` (checked), so it is *not*
    caught here and correctly surfaces as 400. The service's `reasonOf()`
    `IllegalStateException` (a RuntimeException) maps to 500 as intended.
  - The 500 message is the fixed generic `"Internal error"` (matches openapi example);
    the exception detail is never leaked to the client.
- Never emits `UNAUTHENTICATED`/401 (T10's job), nor `FORBIDDEN`/`CONFLICT`/`VALIDATION`
  (no admin path). A small private `envelope(ErrorCode, String)` helper builds
  `ErrorEnvelopeDto`.

### 3. `ResourceNotFoundException extends RuntimeException`

Tiny internal signal raised by the controller when a detail route's `Optional` is empty, so
the controller never hand-constructs an envelope and the advice stays the single emission
point. Package-private to `..controller..`.

## Data flow

```
HTTP GET /integration-registry/v1/vendor-services/{id}
  → VendorController: extract X-IPIMS-* headers, build OutboundAuth
  → VendorService.getVendorServiceDetail(orgId, id, auth)   [orchestration; 404-vs-partial decided here]
      → Optional.empty()  → throw ResourceNotFoundException → advice → 404 NOT_FOUND envelope
      → Optional.of(dto)  → return dto                       → 200 (dto may carry empty payload + unavailable_products[])
  (any uncaught RuntimeException anywhere) → advice → 500 INTERNAL envelope
  (missing X-IPIMS-* header on a direct call) → Spring → 400 (not 401, not 500)
```

## Error handling

| Condition | Status | Body | Owner |
|---|---|---|---|
| Happy path / partial unavailability / all-adapter-failure | 200 | route DTO (+ `unavailable_products[]`) | service assembles, controller passes through |
| Detail route, service signals not-found | 404 | `NOT_FOUND` envelope | controller throws, advice emits |
| Unexpected internal error (e.g. `reasonOf` `IllegalStateException`) | 500 | `INTERNAL` envelope | advice |
| Missing identity header (direct call, no T10) | 400 | Spring default | Spring (defensive only) |
| Invalid/absent identity (production) | 401 | `UNAUTHENTICATED` envelope | **T10 filter chain — NOT this plan** |

## Testing strategy (TDD)

`VendorControllerTest` — `@WebMvcTest(VendorController.class)` + `@MockitoBean VendorService`
(Spring Boot 4 replacement for `@MockBean`), `MockMvc`:

1. `GET /vendor-services` → 200, body delegates to mocked `listVendorServices`.
2. `GET /vendors` → 200, delegates to `listVendors`.
3. `GET /vendor-services/{id}` present Optional → 200 body.
4. `GET /vendors/{id}` present Optional → 200 body.
5. `GET /vendor-services/{id}` `Optional.empty()` → 404 with `NOT_FOUND` envelope
   (`error.code`, `error.message` snake_case).
6. `GET /vendors/{id}` `Optional.empty()` → 404 `NOT_FOUND`.
7. **200-empty-payload-not-404**: service returns present `VendorServiceDetailResponse`
   with empty `data_sources[]` + populated `unavailable_products[]` → 200 (asserts the
   controller does NOT treat empty payload as 404).
8. **All-adapter-failure**: `listVendorServices` returns response with empty
   `vendor_services` + populated `unavailable_products[]` → 200 pass-through.
9. **500 envelope**: mocked service throws `IllegalStateException` → 500 with `INTERNAL`
   envelope, generic message, no leak.
10. **Identity-header forwarding**: capture the `OutboundAuth` argument; assert both
    `X-IPIMS-ORG-ID`/`X-IPIMS-USER-ID` values are forwarded and `orgId` arg matches header.
11. **Missing-header → 400 (never 401)**: request without `X-IPIMS-ORG-ID` → 400; assert
    status is NOT 401 and the controller never produced an `UNAUTHENTICATED` body. Documents
    that the controller has no auth path.

**Deferred (accepted risk):** the genuine "filter chain returns 401 before the handler"
assertion requires T10's `SecurityFilterChain`, which does not exist. Asserting it now would
test a fabricated chain. Deferred to T10's own suite / Plan 04's full-context suite once T10
lands. Recorded in the work-plan dashboard.

## Non-goals (unchanged from work plan)

Response assembly / metadata / 404-vs-partial decision (Plan 02); DTO shapes + serialization
(Plan 01); inbound JWT/401/health-exempt + outbound Class 3 headers (T10); full-context
integration tests (Plan 04); pagination/sorting/filtering (client-side per RFC).

## Quality gates

ArchUnit (controller imports only `..service..`/`..controller.dto..`/`..auth..`), PMD
(`pmd-ruleset.xml`), Spotless (`./mvnw spotless:apply` is authoritative). Build:
`./mvnw verify` (this plan adds no Docker-dependent tests; the `@WebMvcTest` slice is
Docker-free).
