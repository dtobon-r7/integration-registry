package com.rapid7.integrationregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.aggregator.VendorAggregator;
import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
