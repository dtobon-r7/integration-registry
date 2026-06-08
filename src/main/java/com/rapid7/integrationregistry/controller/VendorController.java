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
 * so it never returns 401 itself; it observes the {@code X-IPIMS-*} identity the chain validated
 * and forwards it downstream via {@link OutboundAuth}.
 *
 * <p>The 404-vs-partial-unavailability decision is the service's: {@code getVendorServiceDetail} /
 * {@code getVendorDetail} return {@code Optional.empty()} only when fresh AND stale confirm
 * emptiness. This controller maps {@code Optional.empty()} to a 404 {@code NOT_FOUND} envelope (via
 * {@link ResourceNotFoundException} + {@link ReadApiExceptionHandler}) and a present result to 200
 * — including the empty-payload-with-populated-{@code unavailable_products[]} case, which is a
 * normal 200 the controller passes through unexamined.
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
