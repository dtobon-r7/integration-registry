# VendorController and read-path HTTP edge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mount the four locked read routes under `/integration-registry/v1/` on a thin `VendorController` that maps `VendorService` outcomes to HTTP status + the JSON error envelope, with a centralized `@RestControllerAdvice`.

**Architecture:** Three classes in `..controller..` (allowed imports: `..service..`, `..controller.dto..`, `..auth..` — ArchUnit-enforced). `VendorController` extracts identity headers, builds `OutboundAuth`, delegates to `VendorService`, returns DTOs; detail routes throw `ResourceNotFoundException` on `Optional.empty()`. `ReadApiExceptionHandler` (`@RestControllerAdvice`) is the single error-envelope emitter: `ResourceNotFoundException` → 404 `NOT_FOUND`, `RuntimeException` → 500 `INTERNAL`. Missing identity headers fall through to Spring's natural 400 (never 401/500). 404-vs-partial logic lives entirely in the service; the controller only maps the `Optional`.

**Tech Stack:** Java 25, Spring Boot 4.0.6 (Spring Framework 7.0.7), Spring MVC, Jackson 3 (`tools.jackson`), JUnit 5, `@WebMvcTest` + `@MockitoBean` + `MockMvc`, AssertJ.

---

## File structure

- Create: `src/main/java/com/rapid7/integrationregistry/controller/VendorController.java`
- Create: `src/main/java/com/rapid7/integrationregistry/controller/ReadApiExceptionHandler.java`
- Create: `src/main/java/com/rapid7/integrationregistry/controller/ResourceNotFoundException.java`
- Test: `src/test/java/com/rapid7/integrationregistry/controller/VendorControllerTest.java`

Conventions verified against the codebase:
- `VendorService` methods (wp-02, merged): `listVendorServices(String, OutboundAuth)`, `listVendors(String, OutboundAuth)`, `Optional<VendorServiceDetailResponse> getVendorServiceDetail(String, String, OutboundAuth)`, `Optional<VendorDetailResponse> getVendorDetail(String, String, OutboundAuth)`.
- `OutboundAuth.of(Map<String,String>)` builds the framework-neutral carrier.
- `ErrorEnvelopeDto(ErrorBody error)`, `ErrorEnvelopeDto.ErrorBody(ErrorCode code, String message)`; `ErrorCode.NOT_FOUND`, `ErrorCode.INTERNAL`.
- `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean` (Spring 7; `@MockBean` is removed).
- Identity header names: `X-IPIMS-ORG-ID`, `X-IPIMS-USER-ID`. Path params: `vendor_service_id`, `vendor_id`.
- No `server.servlet.context-path` — the controller carries the full `/integration-registry/v1` prefix.

---

## Task 1: ResourceNotFoundException

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/controller/ResourceNotFoundException.java`

- [ ] **Step 1: Write the class** (no test of its own — it is exercised via the controller slice tests in Task 4)

```java
package com.rapid7.integrationregistry.controller;

/**
 * Raised by {@link VendorController} when a detail route's {@code VendorService} result is {@code
 * Optional.empty()} — the service's signal that fresh AND stale data both confirm the org has no
 * integrations under the requested id (RFC-001 §"404 vs partial unavailability"). Translated to a
 * 404 {@code NOT_FOUND} envelope by {@link ReadApiExceptionHandler}. Exists so the controller never
 * hand-builds an error envelope; the advice stays the single emission point.
 */
class ResourceNotFoundException extends RuntimeException {

  ResourceNotFoundException(String message) {
    super(message);
  }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS (if offline cache misses, drop `-o`).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/controller/ResourceNotFoundException.java
git commit -m "feat(track-09/wp-03): ResourceNotFoundException — controller's 404 signal

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: ReadApiExceptionHandler (centralized error envelope)

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/controller/ReadApiExceptionHandler.java`

The handler is verified in Task 4 (404 + 500 paths through `MockMvc`). Writing it before the controller so the controller's `orElseThrow` has a translator.

- [ ] **Step 1: Write the class**

```java
package com.rapid7.integrationregistry.controller;

import com.rapid7.integrationregistry.controller.dto.ErrorCode;
import com.rapid7.integrationregistry.controller.dto.ErrorEnvelopeDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place read-path error envelopes are constructed (work plan: "Centralized
 * error-envelope emission for the read path"). Emits only {@code NOT_FOUND} (404) and {@code
 * INTERNAL} (500); never {@code UNAUTHENTICATED}/401 (T10's inbound filter chain owns that) and
 * never {@code FORBIDDEN}/{@code CONFLICT}/{@code VALIDATION} (the Registry has no write/admin path).
 *
 * <p>The catch-all binds {@link RuntimeException}, deliberately NOT {@link Exception}: Spring's
 * {@code MissingRequestHeaderException} is a checked {@code ServletException}, so an absent identity
 * header stays Spring's natural 400 instead of being swallowed into a 500. {@code VendorService}'s
 * {@code reasonOf()} {@code IllegalStateException} (a {@code RuntimeException}) maps to 500 here.
 */
@RestControllerAdvice
class ReadApiExceptionHandler {

  private static final String INTERNAL_MESSAGE = "Internal error";

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ErrorEnvelopeDto> handleNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(envelope(ErrorCode.NOT_FOUND, ex.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ErrorEnvelopeDto> handleInternal(RuntimeException ex) {
    // The exception detail is intentionally not leaked to the client; the message is fixed.
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(envelope(ErrorCode.INTERNAL, INTERNAL_MESSAGE));
  }

  private static ErrorEnvelopeDto envelope(ErrorCode code, String message) {
    return new ErrorEnvelopeDto(new ErrorEnvelopeDto.ErrorBody(code, message));
  }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/controller/ReadApiExceptionHandler.java
git commit -m "feat(track-09/wp-03): ReadApiExceptionHandler — centralized 404/500 envelope

RuntimeException catch-all (not Exception) keeps a missing-identity-header
ServletException as Spring's 400; reasonOf IllegalStateException -> 500.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: VendorController (the four routes)

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/controller/VendorController.java`

Fully verified in Task 4. Written here so the slice test has a target.

- [ ] **Step 1: Write the class**

```java
package com.rapid7.integrationregistry.controller;

import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.controller.dto.VendorsResponse;
import com.rapid7.integrationregistry.service.VendorService;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary of the read API (RFC-001 §Spring layer boundaries — "HTTP only: parse request,
 * extract headers, serialize responses; no cache, no fan-out, no business logic"). Mounts the four
 * locked routes under {@code /integration-registry/v1/} behind T10's inbound identity filter chain,
 * so it never returns 401 itself; it observes the {@code X-IPIMS-*} identity the chain validated and
 * forwards it downstream via {@link OutboundAuth}.
 *
 * <p>The 404-vs-partial-unavailability decision is the service's: {@code getVendorServiceDetail} /
 * {@code getVendorDetail} return {@code Optional.empty()} only when fresh AND stale confirm
 * emptiness. This controller maps {@code Optional.empty()} to a 404 {@code NOT_FOUND} envelope (via
 * {@link ResourceNotFoundException} + {@link ReadApiExceptionHandler}) and a present result to 200 —
 * including the empty-payload-with-populated-{@code unavailable_products[]} case, which is a normal
 * 200 the controller passes through unexamined.
 */
@RestController
@RequestMapping("/integration-registry/v1")
public class VendorController {

  static final String ORG_ID_HEADER = "X-IPIMS-ORG-ID";
  static final String USER_ID_HEADER = "X-IPIMS-USER-ID";

  private final VendorService vendorService;

  public VendorController(VendorService vendorService) {
    this.vendorService = Objects.requireNonNull(vendorService, "vendorService");
  }

  @GetMapping("/vendor-services")
  public VendorServicesResponse listVendorServices(
      @RequestHeader(ORG_ID_HEADER) String orgId, @RequestHeader(USER_ID_HEADER) String userId) {
    return vendorService.listVendorServices(orgId, auth(orgId, userId));
  }

  @GetMapping("/vendor-services/{vendor_service_id}")
  public VendorServiceDetailResponse getVendorServiceDetail(
      @PathVariable("vendor_service_id") String vendorServiceId,
      @RequestHeader(ORG_ID_HEADER) String orgId,
      @RequestHeader(USER_ID_HEADER) String userId) {
    return vendorService
        .getVendorServiceDetail(orgId, vendorServiceId, auth(orgId, userId))
        .orElseThrow(() -> new ResourceNotFoundException("Vendor service not found in this org"));
  }

  @GetMapping("/vendors")
  public VendorsResponse listVendors(
      @RequestHeader(ORG_ID_HEADER) String orgId, @RequestHeader(USER_ID_HEADER) String userId) {
    return vendorService.listVendors(orgId, auth(orgId, userId));
  }

  @GetMapping("/vendors/{vendor_id}")
  public VendorDetailResponse getVendorDetail(
      @PathVariable("vendor_id") String vendorId,
      @RequestHeader(ORG_ID_HEADER) String orgId,
      @RequestHeader(USER_ID_HEADER) String userId) {
    return vendorService
        .getVendorDetail(orgId, vendorId, auth(orgId, userId))
        .orElseThrow(() -> new ResourceNotFoundException("Vendor not found in this org"));
  }

  /** Carry the validated identity headers downstream for the outbound Class 3 forward (T10). */
  private static OutboundAuth auth(String orgId, String userId) {
    return OutboundAuth.of(Map.of(ORG_ID_HEADER, orgId, USER_ID_HEADER, userId));
  }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/controller/VendorController.java
git commit -m "feat(track-09/wp-03): VendorController — four read routes under /v1

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: VendorControllerTest (@WebMvcTest slice)

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/controller/VendorControllerTest.java`

This is the first `@WebMvcTest` in the repo. It boots only `VendorController` + `ReadApiExceptionHandler` (advice is auto-detected by the MVC slice) with a `@MockitoBean VendorService`. Build small fixture DTOs inline.

- [ ] **Step 1: Write the failing test**

```java
package com.rapid7.integrationregistry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.ResponseMetadataDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.service.VendorService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VendorController.class)
class VendorControllerTest {

  private static final String ORG = "org-123";
  private static final String USER = "user-456";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private VendorService vendorService;

  private static ResponseMetadataDto meta() {
    return new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
  }

  @Test
  void listVendorServices_returns200() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenReturn(new VendorServicesResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metadata.mapping_version").value("v1.42.0"))
        .andExpect(jsonPath("$.unavailable_products").isArray());
  }

  @Test
  void listVendors_returns200() throws Exception {
    when(vendorService.listVendors(eq(ORG), any()))
        .thenReturn(new com.rapid7.integrationregistry.controller.dto.VendorsResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(get("/integration-registry/v1/vendors").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendors").isArray());
  }

  @Test
  void vendorServiceDetail_present_returns200() throws Exception {
    when(vendorService.getVendorServiceDetail(eq(ORG), eq("microsoft-defender"), any()))
        .thenReturn(Optional.of(detail("microsoft-defender")));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services/microsoft-defender")
                .header(ORG_ID(), ORG)
                .header(USER_ID(), USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendor_service_id").value("microsoft-defender"));
  }

  @Test
  void vendorServiceDetail_empty_returns404WithEnvelope() throws Exception {
    when(vendorService.getVendorServiceDetail(eq(ORG), eq("ghost"), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/integration-registry/v1/vendor-services/ghost").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").isString());
  }

  @Test
  void vendorDetail_empty_returns404() throws Exception {
    when(vendorService.getVendorDetail(eq(ORG), eq("ghost"), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/integration-registry/v1/vendors/ghost").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void vendorServiceDetail_emptyPayloadWithUnavailable_returns200NotFalse404() throws Exception {
    // Present Optional with empty data_sources + populated unavailable_products: partial
    // unavailability, MUST be 200, NOT 404. The controller passes the present result through.
    VendorServiceDetailResponse partial =
        new VendorServiceDetailResponse(
            "microsoft-defender",
            "Microsoft Defender",
            "microsoft",
            "Microsoft",
            "edr",
            com.rapid7.integrationregistry.controller.dto.HealthState.MISSING_DATA,
            Instant.parse("2026-04-23T10:00:00Z"),
            List.of(),
            List.of(
                new com.rapid7.integrationregistry.controller.dto.UnavailableProductDto(
                    "InsightIDR", false,
                    com.rapid7.integrationregistry.controller.dto.UnavailableReason.TIMEOUT, null)),
            meta());
    when(vendorService.getVendorServiceDetail(eq(ORG), eq("microsoft-defender"), any()))
        .thenReturn(Optional.of(partial));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services/microsoft-defender")
                .header(ORG_ID(), ORG)
                .header(USER_ID(), USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data_sources").isEmpty())
        .andExpect(jsonPath("$.unavailable_products[0].product_name").value("InsightIDR"));
  }

  @Test
  void listVendorServices_allAdapterFailure_returns200PassThrough() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenReturn(
            new VendorServicesResponse(
                List.of(),
                List.of(
                    new com.rapid7.integrationregistry.controller.dto.UnavailableProductDto(
                        "InsightIDR", false,
                        com.rapid7.integrationregistry.controller.dto.UnavailableReason.UPSTREAM_5XX, null)),
                meta()));

    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendor_services").isEmpty())
        .andExpect(jsonPath("$.unavailable_products[0].product_name").value("InsightIDR"));
  }

  @Test
  void serviceThrows_returns500InternalEnvelope() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenThrow(new IllegalStateException("boom: leaked detail"));

    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL"))
        .andExpect(jsonPath("$.error.message").value("Internal error"));
  }

  @Test
  void forwardsIdentityHeadersToService() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenReturn(new VendorServicesResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(ORG_ID(), ORG).header(USER_ID(), USER))
        .andExpect(status().isOk());

    ArgumentCaptor<OutboundAuth> captor = ArgumentCaptor.forClass(OutboundAuth.class);
    verify(vendorService).listVendorServices(eq(ORG), captor.capture());
    assertThat(captor.getValue().headers())
        .containsEntry("X-IPIMS-ORG-ID", ORG)
        .containsEntry("X-IPIMS-USER-ID", USER);
  }

  @Test
  void missingOrgIdHeader_returns400NotUnauthenticated() throws Exception {
    // No SecurityFilterChain exists yet (T10). A direct call missing the identity header must NOT
    // be the controller's job to 401 — it falls through to Spring's natural 400. This documents
    // that the controller has no auth path and never self-emits 401/UNAUTHENTICATED.
    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(USER_ID(), USER))
        .andExpect(status().isBadRequest());
  }

  // Header-name helpers keep the literals in one place and mirror the controller constants.
  private static String ORG_ID() {
    return "X-IPIMS-ORG-ID";
  }

  private static String USER_ID() {
    return "X-IPIMS-USER-ID";
  }

  private static VendorServiceDetailResponse detail(String id) {
    return new VendorServiceDetailResponse(
        id,
        "Microsoft Defender",
        "microsoft",
        "Microsoft",
        "edr",
        com.rapid7.integrationregistry.controller.dto.HealthState.HEALTHY,
        Instant.parse("2026-04-23T10:00:00Z"),
        List.of(),
        List.of(),
        meta());
  }
}
```

- [ ] **Step 2: Run the test to verify it passes (controller + advice already implemented in Tasks 1-3)**

Run: `./mvnw -q -o test -Dtest=VendorControllerTest`
Expected: all tests PASS. If `@MockitoBean` import fails to resolve, confirm the path is `org.springframework.test.context.bean.override.mockito.MockitoBean` (Spring 7). If a DTO constructor arity differs, open the DTO under `controller/dto/` and match the exact record components (do not guess).

- [ ] **Step 3: Format**

Run: `./mvnw -q spotless:apply`
Expected: reformats the new files to Google Java Format (authoritative — do not hand-format).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/controller/VendorControllerTest.java
git commit -m "test(track-09/wp-03): @WebMvcTest slice for VendorController

200 per route; 404 both detail routes; 200-empty-payload-not-404;
all-adapter-failure 200 pass-through; 500 INTERNAL envelope; identity
forwarding; missing-header -> 400 (never 401). The genuine filter-chain
401-before-handler assertion is deferred to T10 / Plan 04.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Full verify (ArchUnit + PMD + Spotless + all tests)

**Files:** none (gate run).

- [ ] **Step 1: Run the full build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Specifically: `LayerDependencyRulesTest` passes (controller imports only `..service..`/`..controller.dto..`/`..auth..`), PMD clean on the three new classes, Spotless check clean. (Docker is required for the unrelated T07 cache tests; if Docker is unavailable, run `./mvnw verify -Dtest='!*CacheIntegration*,!*Valkey*'` is NOT a substitute — note the limitation and run the controller slice + ArchUnit + PMD + Spotless explicitly: `./mvnw -q test -Dtest=VendorControllerTest,LayerDependencyRulesTest` then `./mvnw -q pmd:check spotless:check`.)

- [ ] **Step 2: Confirm no boundary leak**

Run: `./mvnw -q test -Dtest=LayerDependencyRulesTest`
Expected: PASS — proves the controller introduced no forbidden dependency.

- [ ] **Step 3: Commit any Spotless fixups** (only if `verify` reformatted anything)

```bash
git add -A && git commit -m "style(track-09/wp-03): spotless formatting

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-review checklist (done at plan-write time)

- **Spec coverage:** four routes (Task 3) ✓; path-param binding + identity observation (Task 3) ✓; 200/404/500 mapping (Tasks 3-4) ✓; centralized envelope, never 401/FORBIDDEN/CONFLICT/VALIDATION (Task 2) ✓; `@WebMvcTest` covering 404 both routes, 200-empty-not-404, all-failure 200, envelope structure (Task 4) ✓; the "401 via filter chain" signal — deferred (accepted risk, documented in spec + dashboard) ✓.
- **Placeholders:** none — every code step is complete.
- **Type consistency:** `OutboundAuth.of(Map)`, `ErrorEnvelopeDto.ErrorBody(ErrorCode, String)`, `VendorService` four-method signatures, `@MockitoBean` FQN — all verified against merged code / resolved classpath.
- **Risk note for the implementer:** the test instantiates DTO records inline. If any record's component list differs from what's shown, read the DTO source under `controller/dto/` and match exactly — do not invent constructors. Verify `VendorServiceDetailResponse` / `VendorServicesResponse` / `VendorsResponse` / `VendorDetailResponse` / `ResponseMetadataDto` / `UnavailableProductDto` constructor arities against source before running.
