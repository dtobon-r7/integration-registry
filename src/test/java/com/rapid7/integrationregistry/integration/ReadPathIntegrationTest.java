package com.rapid7.integrationregistry.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import com.rapid7.integrationregistry.controller.dto.UnavailableProductDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableReason;
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServiceCardNestedDto;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * Full-context read-path integration suite (WP-04). Boots the real read path against a
 * Testcontainers Valkey with adapters faked, and proves the six track exit-criteria scenarios
 * end-to-end over HTTP.
 */
class ReadPathIntegrationTest extends ReadPathTestSupport {

  @Autowired private FanOutCoordinator coordinator;

  @Test
  void contextBoots_withExactlyTwoStubAdapters() {
    // Arrange — context booted via @SpringBootTest; nothing per-test needed.

    // Act — both stub adapters are injected and the coordinator wired.
    // Assert — the two stubs are present with the expected product names; the real
    // InsightConnectAdapter was evicted by the name-colliding stub bean.
    assertThat(insightConnectAdapter.productName()).isEqualTo(INSIGHT_CONNECT);
    assertThat(insightIdrAdapter.productName()).isEqualTo(INSIGHT_IDR);
    assertThat(coordinator).isNotNull();
  }

  @Test
  void vendorServices_cacheHit_returnsCacheHitTrue_andSkipsAdapters() {
    // Arrange — both products pre-seeded fresh; adapters left unconfigured (must not be called).
    Instant idrFetchedAt = Instant.parse("2026-06-01T09:00:00Z");
    Instant iconFetchedAt = Instant.parse("2026-06-01T10:00:00Z");
    seedFresh(
        INSIGHT_IDR,
        fetchResult(
            idrFetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    seedFresh(
        INSIGHT_CONNECT,
        fetchResult(
            iconFetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — fresh on every product => cache_hit true; as_of is the OLDEST contributing fetch;
    // adapters never called; mapping_version from the test bundle.
    assertThat(body.metadata().cacheHit()).isTrue();
    assertThat(body.metadata().asOf()).isEqualTo(idrFetchedAt);
    assertThat(body.metadata().mappingVersion()).isEqualTo("v1.0.0-test");
    assertThat(body.unavailableProducts()).isEmpty();
    assertThat(body.vendorServices()).isNotEmpty();
    assertThat(insightIdrAdapter.callCount()).isZero();
    assertThat(insightConnectAdapter.callCount()).isZero();
  }

  @Test
  void vendorServices_cacheMiss_fetchesBoth_andReportsCacheHitFalse() {
    // Arrange — empty cache (flushed in @BeforeEach); both adapters return data.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — both fetched => cache_hit false; both adapters invoked; payload present.
    assertThat(body.metadata().cacheHit()).isFalse();
    assertThat(body.unavailableProducts()).isEmpty();
    assertThat(body.vendorServices()).isNotEmpty();
    assertThat(insightIdrAdapter.callCount()).isEqualTo(1);
    assertThat(insightConnectAdapter.callCount()).isEqualTo(1);
  }

  @Test
  void vendorServices_partialWithStale_servesStale_andReportsStaleUnavailable() {
    // Arrange — IDR: stale-only + transient failure => coordinator serves stale.
    Instant staleFetchedAt = Instant.parse("2026-05-30T06:00:00Z");
    Instant iconFetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    seedStaleOnly(
        INSIGHT_IDR,
        fetchResult(
            staleFetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY)));
    insightIdrAdapter.willThrow(new AdapterUpstreamException("503 from IDR"));
    insightConnectAdapter.willReturn(
        fetchResult(
            iconFetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — IDR served stale: in unavailable_products with stale:true + stale_since; as_of is
    // the oldest contributing fetch (the stale IDR fetch); the grid still carries IDR's data.
    assertThat(body.metadata().cacheHit()).isFalse();
    assertThat(body.metadata().asOf()).isEqualTo(staleFetchedAt);
    UnavailableProductDto idr =
        body.unavailableProducts().stream()
            .filter(u -> u.productName().equals(INSIGHT_IDR))
            .findFirst()
            .orElseThrow();
    assertThat(idr.stale()).isTrue();
    assertThat(idr.staleSince()).isEqualTo(staleFetchedAt);
    assertThat(body.vendorServices()).isNotEmpty();
  }

  @Test
  void vendorServices_partialWithOmission_omitsProduct_andReportsReason() {
    // Arrange — IDR: no stale + permanent auth failure => omitted entirely; ICON returns data.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willThrow(new AdapterAuthException("401 from IDR"));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(INSIGHT_CONNECT, "plugin_name", "jira", IntegrationStatus.HEALTHY)));

    // Act
    VendorServicesResponse body =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .body(VendorServicesResponse.class);

    // Assert — IDR omitted: stale:false + reason auth_failure, no stale_since; ICON present.
    UnavailableProductDto idr =
        body.unavailableProducts().stream()
            .filter(u -> u.productName().equals(INSIGHT_IDR))
            .findFirst()
            .orElseThrow();
    assertThat(idr.stale()).isFalse();
    assertThat(idr.reason()).isEqualTo(UnavailableReason.AUTH_FAILURE);
    assertThat(idr.staleSince()).isNull();
    assertThat(body.vendorServices()).isNotEmpty();
  }

  @Test
  void vendorServices_allAdaptersFail_returns200_withEmptyArray_andOneEntryPerProduct() {
    // Arrange — both throw, no stale anywhere.
    insightIdrAdapter.willThrow(new AdapterUpstreamException("503 from IDR"));
    insightConnectAdapter.willThrow(new AdapterUpstreamException("503 from ICON"));

    // Act
    ResponseEntity<VendorServicesResponse> response =
        get("/integration-registry/v1/vendor-services")
            .retrieve()
            .toEntity(VendorServicesResponse.class);

    // Assert — 200 (partial/total unavailability is never a 5xx); empty grid; one entry per
    // failed product, distinguishing "couldn't reach anything" from "org has no integrations".
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    VendorServicesResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.vendorServices()).isEmpty();
    assertThat(body.unavailableProducts())
        .extracting(UnavailableProductDto::productName)
        .containsExactlyInAnyOrder(INSIGHT_IDR, INSIGHT_CONNECT);
  }

  @Test
  void vendorDetail_multiService_returnsNestedProjection_withRollups() {
    // Arrange — IDR contributes to both MS services; ICON also contributes to both. A WARNING on
    // one instance makes the vendor-level rollup observably non-trivial.
    Instant fetchedAt = Instant.parse("2026-06-02T08:00:00Z");
    insightIdrAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_IDR,
                "product_type",
                "microsoft-defender-endpoint",
                IntegrationStatus.HEALTHY),
            integration(
                INSIGHT_IDR, "product_type", "microsoft-sentinel", IntegrationStatus.WARNING)));
    insightConnectAdapter.willReturn(
        fetchResult(
            fetchedAt,
            integration(
                INSIGHT_CONNECT, "plugin_name", "microsoft-defender", IntegrationStatus.HEALTHY),
            integration(
                INSIGHT_CONNECT, "plugin_name", "microsoft-sentinel", IntegrationStatus.HEALTHY)));

    // Act
    VendorDetailResponse body =
        get("/integration-registry/v1/vendors/microsoft")
            .retrieve()
            .body(VendorDetailResponse.class);

    // Assert — vendor header present; two nested vendor services; vendor-level rollups populated.
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.vendorName()).isEqualTo("Microsoft");
    assertThat(body.vendorServices())
        .extracting(VendorServiceCardNestedDto::vendorServiceId)
        .containsExactlyInAnyOrder("microsoft-defender", "microsoft-sentinel");
    assertThat(body.aggregateHealth()).isNotNull();
    assertThat(body.lastUpdated()).isNotNull();
    assertThat(body.unavailableProducts()).isEmpty();
  }
}
