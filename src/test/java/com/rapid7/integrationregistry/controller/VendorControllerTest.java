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
import com.rapid7.integrationregistry.controller.dto.HealthState;
import com.rapid7.integrationregistry.controller.dto.ResponseMetadataDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableProductDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableReason;
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.controller.dto.VendorsResponse;
import com.rapid7.integrationregistry.service.VendorService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link VendorController}: boots only the controller and the
 * auto-detected {@link ReadApiExceptionHandler} advice, with {@link VendorService} mocked. Proves
 * the HTTP mapping (200 per route, 404 on the detail-route not-found signal, 200 pass-through for
 * partial/total unavailability, 500 {@code INTERNAL} envelope), identity-header forwarding, and
 * that the controller never self-emits 401 (a missing identity header falls through to Spring's
 * 400).
 *
 * <p>The genuine "filter chain returns 401 before the handler" assertion requires T10's {@code
 * SecurityFilterChain}, which is not yet built; asserting it now would test a fabricated chain. It
 * is deferred to T10's own suite / Plan 04's full-context suite.
 */
@WebMvcTest(VendorController.class)
class VendorControllerTest {

  private static final String ORG = "org-123";
  private static final String USER = "user-456";
  // Hard-coded literals (NOT VendorController's constants) so these tests lock the external wire
  // contract: X-IPIMS-* is injected by Kong and pinned in openapi.json. Referencing the controller
  // constant would let a contract-breaking rename of its value track silently and still pass; with
  // the literal, the controller would then require a header the test never sends -> 400 -> fail.
  private static final String ORG_ID_HEADER = "X-IPIMS-ORG-ID";
  private static final String USER_ID_HEADER = "X-IPIMS-USER-ID";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private VendorService vendorService;

  private static ResponseMetadataDto meta() {
    return new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
  }

  private static VendorServiceDetailResponse detail(String id) {
    return new VendorServiceDetailResponse(
        id,
        "Microsoft Defender",
        "microsoft",
        "Microsoft",
        "edr",
        HealthState.HEALTHY,
        Instant.parse("2026-04-23T10:00:00Z"),
        List.of(),
        List.of(),
        meta());
  }

  @Test
  void listVendorServices_returns200() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenReturn(new VendorServicesResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metadata.mapping_version").value("v1.42.0"))
        .andExpect(jsonPath("$.unavailable_products").isArray());
  }

  @Test
  void listVendors_returns200() throws Exception {
    when(vendorService.listVendors(eq(ORG), any()))
        .thenReturn(new VendorsResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendors")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
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
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendor_service_id").value("microsoft-defender"));
  }

  @Test
  void vendorDetail_present_returns200() throws Exception {
    when(vendorService.getVendorDetail(eq(ORG), eq("microsoft"), any()))
        .thenReturn(
            Optional.of(
                new VendorDetailResponse(
                    "microsoft",
                    "Microsoft",
                    HealthState.HEALTHY,
                    Instant.parse("2026-04-23T10:00:00Z"),
                    List.of(),
                    List.of(),
                    meta())));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendors/microsoft")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendor_id").value("microsoft"))
        .andExpect(jsonPath("$.vendor_name").value("Microsoft"));
  }

  @Test
  void vendorServiceDetail_empty_returns404WithEnvelope() throws Exception {
    when(vendorService.getVendorServiceDetail(eq(ORG), eq("ghost"), any()))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services/ghost")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").isString());
  }

  @Test
  void vendorDetail_empty_returns404() throws Exception {
    when(vendorService.getVendorDetail(eq(ORG), eq("ghost"), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/integration-registry/v1/vendors/ghost")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void vendorServiceDetail_emptyPayloadWithUnavailable_returns200Not404() throws Exception {
    // Present Optional with empty data_sources + populated unavailable_products: partial
    // unavailability, MUST be 200, NOT 404. The controller passes the present result through.
    VendorServiceDetailResponse partial =
        new VendorServiceDetailResponse(
            "microsoft-defender",
            "Microsoft Defender",
            "microsoft",
            "Microsoft",
            "edr",
            HealthState.MISSING_DATA,
            Instant.parse("2026-04-23T10:00:00Z"),
            List.of(),
            List.of(
                new UnavailableProductDto("InsightIDR", false, UnavailableReason.TIMEOUT, null)),
            meta());
    when(vendorService.getVendorServiceDetail(eq(ORG), eq("microsoft-defender"), any()))
        .thenReturn(Optional.of(partial));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services/microsoft-defender")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
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
                    new UnavailableProductDto(
                        "InsightIDR", false, UnavailableReason.UPSTREAM_5XX, null)),
                meta()));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vendor_services").isEmpty())
        .andExpect(jsonPath("$.unavailable_products[0].product_name").value("InsightIDR"));
  }

  @Test
  void serviceThrows_returns500InternalEnvelope() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenThrow(new IllegalStateException("boom: leaked detail"));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL"))
        .andExpect(jsonPath("$.error.message").value("Internal error"));
  }

  @Test
  void forwardsIdentityHeadersToService() throws Exception {
    when(vendorService.listVendorServices(eq(ORG), any()))
        .thenReturn(new VendorServicesResponse(List.of(), List.of(), meta()));

    mockMvc
        .perform(
            get("/integration-registry/v1/vendor-services")
                .header(ORG_ID_HEADER, ORG)
                .header(USER_ID_HEADER, USER))
        .andExpect(status().isOk());

    ArgumentCaptor<OutboundAuth> captor = ArgumentCaptor.forClass(OutboundAuth.class);
    verify(vendorService).listVendorServices(eq(ORG), captor.capture());
    assertThat(captor.getValue().headers())
        .containsEntry(ORG_ID_HEADER, ORG)
        .containsEntry(USER_ID_HEADER, USER);
  }

  @Test
  void missingOrgIdHeader_returns400NotUnauthenticated() throws Exception {
    // No SecurityFilterChain exists yet (T10). A direct call missing the identity header is NOT the
    // controller's job to 401 — it falls through to Spring's natural 400. This documents that the
    // controller has no auth path and never self-emits 401/UNAUTHENTICATED.
    mockMvc
        .perform(get("/integration-registry/v1/vendor-services").header(USER_ID_HEADER, USER))
        .andExpect(status().isBadRequest())
        // 400, not 401, and the controller's advice did not run: no error envelope is emitted.
        .andExpect(jsonPath("$.error").doesNotExist());
  }
}
