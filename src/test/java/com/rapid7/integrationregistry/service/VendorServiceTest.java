package com.rapid7.integrationregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import com.rapid7.integrationregistry.aggregator.VendorAggregator;
import com.rapid7.integrationregistry.aggregator.projection.DataSourceDetail;
import com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail;
import com.rapid7.integrationregistry.aggregator.projection.IntegrationTypeCount;
import com.rapid7.integrationregistry.aggregator.projection.VendorCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorScopedView;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceDetail;
import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.HealthState;
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorListEntryDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.controller.dto.VendorsResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VendorServiceTest {

  private static final String ORG = "org-1";
  private static final Instant NOW = Instant.parse("2026-06-05T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private FanOutCoordinator coordinator;
  @Mock private VendorAggregator aggregator;

  private VendorService service;

  @BeforeEach
  void setUp() {
    service = new VendorService(coordinator, aggregator, FIXED);
  }

  @Test
  void listVendorServices_asOf_isOldestFetchedAtAcrossServed() {
    Instant older = Instant.parse("2026-06-05T10:00:00Z");
    Instant newer = Instant.parse("2026-06-05T11:00:00Z");
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(
                VendorServiceFixtures.served("InsightConnect", newer, true),
                VendorServiceFixtures.served("InsightIDR", older, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().asOf()).isEqualTo(older);
  }

  @Test
  void cacheHit_trueOnlyWhenEveryProductFreshTierHit() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(
                VendorServiceFixtures.served("InsightConnect", NOW, true),
                VendorServiceFixtures.served("InsightIDR", NOW, false))); // fetched, not fresh hit
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().cacheHit()).isFalse();
  }

  @Test
  void cacheHit_trueWhenAllFreshTierHits() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().cacheHit()).isTrue();
  }

  @Test
  void mappingVersion_isReadFromAggregator() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty())).thenReturn(List.of());
    when(aggregator.mappingVersion()).thenReturn("v9.9.9");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().mappingVersion()).isEqualTo("v9.9.9");
  }

  @Test
  void asOf_fallsBackToClockWhenNoServedOutcomes() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().asOf()).isEqualTo(NOW);
  }

  @Test
  void unavailableProducts_staleServeCarriesStaleTrueAndStaleSinceAndReason() {
    Instant staleSince = Instant.parse("2026-06-04T00:00:00Z");
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(VendorServiceFixtures.staleServed("InsightIDR", NOW, staleSince, "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).hasSize(1);
    var up = resp.unavailableProducts().get(0);
    assertThat(up.stale()).isTrue();
    assertThat(up.staleSince()).isEqualTo(staleSince);
    assertThat(up.reason().wire()).isEqualTo("timeout");
  }

  @Test
  void unavailableProducts_omittedProductCarriesStaleFalseAndReason() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "auth_failure")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).hasSize(1);
    var up = resp.unavailableProducts().get(0);
    assertThat(up.stale()).isFalse();
    assertThat(up.staleSince()).isNull();
    assertThat(up.reason().wire()).isEqualTo("auth_failure");
  }

  @Test
  void unavailableProducts_cleanFreshProductNotListed() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).isEmpty();
  }

  // ----- F1: listVendors route -----

  @Test
  void listVendors_mapsVendorCardsAndPopulatesEnvelope() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightIDR", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorCards(ArgumentMatchers.anyList()))
        .thenReturn(List.of(new VendorCard("microsoft", "Microsoft", 3)));

    VendorsResponse resp = service.listVendors(ORG, OutboundAuth.empty());

    assertThat(resp.vendors()).hasSize(1);
    VendorListEntryDto entry = resp.vendors().get(0);
    assertThat(entry.vendorId()).isEqualTo("microsoft");
    assertThat(entry.vendorName()).isEqualTo("Microsoft");
    assertThat(entry.vendorServicesCount()).isEqualTo(3);
    // metadata + unavailableProducts envelope is populated (a single fresh hit -> cacheHit true).
    assertThat(resp.metadata().mappingVersion()).isEqualTo("v1.0.0");
    assertThat(resp.metadata().asOf()).isEqualTo(NOW);
    assertThat(resp.metadata().cacheHit()).isTrue();
    assertThat(resp.unavailableProducts()).isEmpty();
  }

  // ----- F2: healthOf maps all 5 IntegrationStatus values to the wire HealthState -----

  @ParameterizedTest
  @EnumSource(IntegrationStatus.class)
  void healthOf_mapsEveryIntegrationStatusToMatchingHealthState(IntegrationStatus status) {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightIDR", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList()))
        .thenReturn(List.of(vendorServiceCardWithHealth(status)));
    when(aggregator.wireCategoryOf(ArgumentMatchers.<VendorServiceCard>any())).thenReturn("siem");

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.vendorServices()).hasSize(1);
    assertThat(resp.vendorServices().get(0).aggregateHealth())
        .isEqualTo(HealthState.valueOf(status.name()));
  }

  // ----- F3: a stale serve flips cache_hit to false -----

  @Test
  void cacheHit_falseWhenOnlyOutcomeIsStaleServe() {
    Instant staleSince = Instant.parse("2026-06-04T00:00:00Z");
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(VendorServiceFixtures.staleServed("InsightIDR", NOW, staleSince, "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.metadata().cacheHit()).isFalse();
  }

  // ----- F4: all-adapter-failure plurality -----

  @Test
  void unavailableProducts_carriesEveryFailedProductWithItsReason() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(
            List.of(
                VendorServiceFixtures.unavailable("InsightConnect", "timeout"),
                VendorServiceFixtures.unavailable("InsightIDR", "auth_failure")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceCards(ArgumentMatchers.anyList())).thenReturn(List.of());

    VendorServicesResponse resp = service.listVendorServices(ORG, OutboundAuth.empty());

    assertThat(resp.unavailableProducts()).hasSize(2);
    assertThat(resp.unavailableProducts())
        .extracting(up -> up.productName(), up -> up.reason().wire())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("InsightConnect", "timeout"),
            org.assertj.core.groups.Tuple.tuple("InsightIDR", "auth_failure"));
    // No Served outcomes -> as_of falls back to the fixed clock NOW.
    assertThat(resp.metadata().asOf()).isEqualTo(NOW);
  }

  // ----- A1: reasonOf build-time guard — T07<->T09 reason coupling -----

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "upstream_5xx", "auth_failure", "no_data"})
  void reasonOf_resolvesEveryReasonCoordinatorCanEmit(String wire) {
    // Sourced from the real producers of these strings:
    //  - AdapterTimeoutException.reasonCode()  == "timeout"     (and
    // OutcomeClassifier.REASON_TIMEOUT)
    //  - AdapterUpstreamException.reasonCode() == "upstream_5xx"
    //  - AdapterAuthException.reasonCode()     == "auth_failure"
    //  - OutcomeClassifier.REASON_NO_DATA      == "no_data"
    assertThat(VendorService.reasonOf(wire)).isNotNull();
  }

  @Test
  void reasonOf_concreteAdapterExceptionReasonCodesAllResolve() {
    List<AdapterException> exceptions =
        List.of(
            new AdapterTimeoutException("t"),
            new AdapterUpstreamException("u"),
            new AdapterAuthException("a"));
    for (AdapterException ex : exceptions) {
      assertThat(VendorService.reasonOf(ex.reasonCode())).isNotNull();
    }
  }

  @Test
  void reasonOf_unknownStringThrowsIllegalState() {
    // Pins the fail-fast contract: an unmapped wire string must blow up, not silently default.
    assertThatThrownBy(() -> VendorService.reasonOf("not_a_real_reason"))
        .isInstanceOf(IllegalStateException.class);
  }

  // ----- detail routes: 404-vs-partial rule -----

  @Test
  void vendorServiceDetail_notFound_whenAggregatorEmptyAndNoUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceDetail(ArgumentMatchers.eq("nope"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.empty());

    Optional<VendorServiceDetailResponse> resp =
        service.getVendorServiceDetail(ORG, "nope", OutboundAuth.empty());

    assertThat(resp).isEmpty();
  }

  @Test
  void vendorServiceDetail_emptyPayload200_whenAggregatorEmptyButProductUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "timeout")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorServiceDetail(
            ArgumentMatchers.eq("ms-defender"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.empty());

    Optional<VendorServiceDetailResponse> resp =
        service.getVendorServiceDetail(ORG, "ms-defender", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorServiceDetailResponse body = resp.get();
    assertThat(body.dataSources()).isEmpty();
    assertThat(body.unavailableProducts()).hasSize(1);
    assertThat(body.vendorServiceId()).isEqualTo("ms-defender");
    assertThat(body.vendorServiceName()).isEqualTo("ms-defender");
    assertThat(body.vendorId()).isEqualTo("unknown");
    assertThat(body.vendorName()).isEqualTo("Unknown");
    assertThat(body.vendorCategory()).isEqualTo("other");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.MISSING_DATA);
    assertThat(body.lastUpdated()).isEqualTo(body.metadata().asOf());
  }

  @Test
  void vendorServiceDetail_present200_mapsProjectionFields() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightIDR", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    VendorServiceDetail projection = vendorServiceDetailFixture();
    when(aggregator.toVendorServiceDetail(
            ArgumentMatchers.eq("ms-defender"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.of(projection));
    when(aggregator.wireCategoryOf(projection)).thenReturn("siem");

    Optional<VendorServiceDetailResponse> resp =
        service.getVendorServiceDetail(ORG, "ms-defender", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorServiceDetailResponse body = resp.get();
    assertThat(body.vendorServiceId()).isEqualTo("ms-defender");
    assertThat(body.vendorServiceName()).isEqualTo("Microsoft Defender");
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.vendorName()).isEqualTo("Microsoft");
    assertThat(body.vendorCategory()).isEqualTo("siem");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.WARNING);
    assertThat(body.lastUpdated()).isEqualTo(Instant.parse("2026-06-02T00:00:00Z"));
    assertThat(body.dataSources()).hasSize(1);
    var ds = body.dataSources().get(0);
    assertThat(ds.dataSourceId()).isEqualTo("ds-1");
    assertThat(ds.status()).isEqualTo(HealthState.WARNING);
    assertThat(ds.integrationsCount()).isEqualTo(1);
    assertThat(ds.integrations()).hasSize(1);
    var in = ds.integrations().get(0);
    assertThat(in.integrationId()).isEqualTo("i-1");
    assertThat(in.integrationLabel()).isEqualTo("Prod tenant");
    assertThat(in.status()).isEqualTo(HealthState.HEALTHY);
  }

  @Test
  void vendorDetail_notFound_whenAggregatorEmptyAndNoUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightConnect", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorScopedView(ArgumentMatchers.eq("nope"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.empty());

    Optional<VendorDetailResponse> resp =
        service.getVendorDetail(ORG, "nope", OutboundAuth.empty());

    assertThat(resp).isEmpty();
  }

  @Test
  void vendorDetail_emptyPayload200_whenAggregatorEmptyButProductUnavailable() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.unavailable("InsightConnect", "upstream_5xx")));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    when(aggregator.toVendorScopedView(
            ArgumentMatchers.eq("microsoft"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.empty());

    Optional<VendorDetailResponse> resp =
        service.getVendorDetail(ORG, "microsoft", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorDetailResponse body = resp.get();
    assertThat(body.vendorServices()).isEmpty();
    assertThat(body.unavailableProducts()).hasSize(1);
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.vendorName()).isEqualTo("Unknown");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.MISSING_DATA);
    assertThat(body.lastUpdated()).isEqualTo(body.metadata().asOf());
  }

  @Test
  void vendorDetail_present200_mapsProjectionFields() {
    when(coordinator.fetchAll(ORG, OutboundAuth.empty()))
        .thenReturn(List.of(VendorServiceFixtures.served("InsightIDR", NOW, true)));
    when(aggregator.mappingVersion()).thenReturn("v1.0.0");
    VendorServiceCard card = vendorServiceCardFixture();
    VendorScopedView projection =
        new VendorScopedView(
            "microsoft",
            "Microsoft",
            1,
            IntegrationStatus.WARNING,
            Instant.parse("2026-06-02T00:00:00Z"),
            List.of(card));
    when(aggregator.toVendorScopedView(
            ArgumentMatchers.eq("microsoft"), ArgumentMatchers.anyList()))
        .thenReturn(Optional.of(projection));
    when(aggregator.wireCategoryOf(card)).thenReturn("siem");

    Optional<VendorDetailResponse> resp =
        service.getVendorDetail(ORG, "microsoft", OutboundAuth.empty());

    assertThat(resp).isPresent();
    VendorDetailResponse body = resp.get();
    assertThat(body.vendorId()).isEqualTo("microsoft");
    assertThat(body.vendorName()).isEqualTo("Microsoft");
    assertThat(body.aggregateHealth()).isEqualTo(HealthState.WARNING);
    assertThat(body.lastUpdated()).isEqualTo(Instant.parse("2026-06-02T00:00:00Z"));
    assertThat(body.vendorServices()).hasSize(1);
    var nested = body.vendorServices().get(0);
    assertThat(nested.vendorServiceId()).isEqualTo("ms-defender");
    assertThat(nested.vendorServiceName()).isEqualTo("Microsoft Defender");
    assertThat(nested.vendorCategory()).isEqualTo("siem");
    assertThat(nested.aggregateHealth()).isEqualTo(HealthState.WARNING);
    assertThat(nested.integrationsConnected()).isEqualTo(1);
  }

  // ----- projection fixtures (respect record invariants) -----

  private static VendorServiceCard vendorServiceCardFixture() {
    return vendorServiceCardWithHealth(IntegrationStatus.WARNING);
  }

  private static VendorServiceCard vendorServiceCardWithHealth(IntegrationStatus health) {
    return new VendorServiceCard(
        "ms-defender",
        "Microsoft Defender",
        "microsoft",
        "Microsoft",
        com.rapid7.integrationregistry.mapping.VendorCategory.SIEM,
        1,
        List.of(new IntegrationTypeCount("SIEM Event Source", 1, 0)),
        List.of("InsightIDR"),
        health,
        Instant.parse("2026-06-02T00:00:00Z"));
  }

  private static VendorServiceDetail vendorServiceDetailFixture() {
    IntegrationDetail integration =
        new IntegrationDetail(
            "i-1",
            "ds-1",
            "Prod tenant",
            IntegrationStatus.HEALTHY,
            Instant.parse("2026-06-01T00:00:00Z"),
            "https://example/config");
    DataSourceDetail dataSource =
        new DataSourceDetail(
            "ds-1",
            "Microsoft Defender",
            "SIEM Event Source",
            "InsightIDR",
            IntegrationStatus.WARNING,
            1,
            List.of(integration));
    return new VendorServiceDetail(
        "ms-defender",
        "Microsoft Defender",
        "microsoft",
        "Microsoft",
        com.rapid7.integrationregistry.mapping.VendorCategory.SIEM,
        1,
        List.of(new IntegrationTypeCount("SIEM Event Source", 1, 0)),
        List.of("InsightIDR"),
        IntegrationStatus.WARNING,
        Instant.parse("2026-06-02T00:00:00Z"),
        List.of(dataSource));
  }
}
