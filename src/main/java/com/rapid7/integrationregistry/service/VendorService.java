package com.rapid7.integrationregistry.service;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.aggregator.VendorAggregator;
import com.rapid7.integrationregistry.aggregator.projection.DataSourceDetail;
import com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail;
import com.rapid7.integrationregistry.aggregator.projection.VendorCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorScopedView;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceDetail;
import com.rapid7.integrationregistry.auth.OutboundAuth;
import com.rapid7.integrationregistry.controller.dto.DataSourceDto;
import com.rapid7.integrationregistry.controller.dto.HealthState;
import com.rapid7.integrationregistry.controller.dto.IntegrationDto;
import com.rapid7.integrationregistry.controller.dto.IntegrationTypeCountDto;
import com.rapid7.integrationregistry.controller.dto.ResponseMetadataDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableProductDto;
import com.rapid7.integrationregistry.controller.dto.UnavailableReason;
import com.rapid7.integrationregistry.controller.dto.VendorDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorListEntryDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceCardDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceCardNestedDto;
import com.rapid7.integrationregistry.controller.dto.VendorServiceDetailResponse;
import com.rapid7.integrationregistry.controller.dto.VendorServicesResponse;
import com.rapid7.integrationregistry.controller.dto.VendorsResponse;
import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import com.rapid7.integrationregistry.coordinator.ProductOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Read-path orchestration brain (RFC-001 §Spring layer boundaries — "knows no HTTP"). One entry
 * point per route: delegate to {@link FanOutCoordinator}, unwrap successful {@link
 * ProductOutcome.Served} integrations, hand them to {@link VendorAggregator} with the route's
 * projection, then assemble the Plan-01 response DTO including the shared {@code metadata} block
 * and {@code unavailable_products[]} envelope. Holds the honesty invariants: {@code as_of} is the
 * oldest contributing fetch, {@code cache_hit} requires every product fresh, and 404 is asserted
 * only when fresh and stale both confirm emptiness.
 */
// CouplingBetweenObjects + TooManyMethods: this class is the read-path assembly point for all four
// routes. By design it touches every Plan-01 response DTO, its nested DTOs, and the aggregator
// projection records it maps from — the coupling and method count are inherent to assembling the
// wire contract from projections, not accidental. One assembly helper per response shape
// (list/detail x vendor/vendor-service) plus the shared spine pushes both metrics past the project
// thresholds. Splitting the assembly across helper classes would only relocate the same fan-in.
// Suppressed locally and justified rather than weakening the project-wide thresholds, mirroring the
// existing precedent on FanOutCoordinator and VendorAggregator.
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@Service
public class VendorService {

  private final FanOutCoordinator coordinator;
  private final VendorAggregator aggregator;
  private final Clock clock;

  public VendorService(FanOutCoordinator coordinator, VendorAggregator aggregator, Clock clock) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public VendorServicesResponse listVendorServices(String orgId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    List<VendorServiceCard> cards = aggregator.toVendorServiceCards(contributing(outcomes));
    List<VendorServiceCardDto> dtos = new ArrayList<>(cards.size());
    for (VendorServiceCard card : cards) {
      dtos.add(toCardDto(card, metadata.asOf()));
    }
    return new VendorServicesResponse(dtos, unavailable, metadata);
  }

  public VendorsResponse listVendors(String orgId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    List<VendorCard> cards = aggregator.toVendorCards(contributing(outcomes));
    List<VendorListEntryDto> dtos = new ArrayList<>(cards.size());
    for (VendorCard card : cards) {
      dtos.add(
          new VendorListEntryDto(card.vendorId(), card.vendorName(), card.vendorServicesCount()));
    }
    return new VendorsResponse(dtos, unavailable, metadata);
  }

  // ----- shared spine -----

  /** Flatten every Served outcome's integrations (stale serves still contribute to the grid). */
  private static List<NormalizedIntegration> contributing(List<ProductOutcome> outcomes) {
    List<NormalizedIntegration> all = new ArrayList<>();
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Served served) {
        all.addAll(served.integrations());
      }
    }
    return all;
  }

  private ResponseMetadataDto metadata(List<ProductOutcome> outcomes) {
    // cache_hit: non-empty AND every product a fresh-tier Served (spec §metadata); the loop seeds
    // true-unless-empty then knocks it down on any stale, fetched, or Unavailable outcome.
    boolean cacheHit = !outcomes.isEmpty();
    Instant oldest = null;
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Served served) {
        if (!(served.cacheHitPerProduct() && !served.stale())) {
          cacheHit = false;
        }
        if (oldest == null || served.fetchedAt().isBefore(oldest)) {
          oldest = served.fetchedAt();
        }
      } else {
        cacheHit = false;
      }
    }
    Instant asOf = (oldest != null) ? oldest : clock.instant();
    return new ResponseMetadataDto(cacheHit, asOf, aggregator.mappingVersion());
  }

  private static List<UnavailableProductDto> unavailableProducts(List<ProductOutcome> outcomes) {
    List<UnavailableProductDto> out = new ArrayList<>();
    for (ProductOutcome o : outcomes) {
      if (o instanceof ProductOutcome.Unavailable u) {
        out.add(new UnavailableProductDto(u.productName(), false, reasonOf(u.reason()), null));
      } else if (o instanceof ProductOutcome.Served served && served.stale()) {
        // stale ⇒ staleReason and staleSince both present, by the ProductOutcome.Served invariant.
        out.add(
            new UnavailableProductDto(
                served.productName(),
                true,
                reasonOf(served.staleReason().orElseThrow()),
                served.staleSince().orElseThrow()));
      }
    }
    return out;
  }

  // ----- translation -----

  static UnavailableReason reasonOf(String wire) {
    return UnavailableReason.fromWire(wire)
        .orElseThrow(
            () ->
                new IllegalStateException("Unknown unavailable reason from coordinator: " + wire));
  }

  static HealthState healthOf(IntegrationStatus status) {
    return switch (status) {
      case HEALTHY -> HealthState.HEALTHY;
      case WARNING -> HealthState.WARNING;
      case ERROR -> HealthState.ERROR;
      case MISSING_DATA -> HealthState.MISSING_DATA;
      case DISABLED -> HealthState.DISABLED;
    };
  }

  /** The DTO requires a non-null {@code lastUpdated}; fall back to {@code as_of} when null. */
  private static Instant lastUpdatedOr(Instant projected, Instant asOf) {
    return projected != null ? projected : asOf;
  }

  private static List<IntegrationTypeCountDto> typeCountDtos(VendorServiceCard card) {
    return card.integrationTypeCounts().stream()
        .map(c -> new IntegrationTypeCountDto(c.integrationType(), c.total(), c.errorCount()))
        .toList();
  }

  // ----- detail routes + 404-vs-partial rule -----

  private static final String UNKNOWN_ID = "unknown";
  private static final String UNKNOWN_NAME = "Unknown";
  private static final String WIRE_CATEGORY_OTHER = "other";

  public Optional<VendorServiceDetailResponse> getVendorServiceDetail(
      String orgId, String vendorServiceId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    Optional<VendorServiceDetail> projection =
        aggregator.toVendorServiceDetail(vendorServiceId, contributing(outcomes));

    if (projection.isPresent()) {
      return Optional.of(toVendorServiceDetailResponse(projection.get(), unavailable, metadata));
    }
    if (unavailable.isEmpty()) {
      return Optional.empty(); // fresh AND stale both confirm emptiness -> 404
    }
    return Optional.of(emptyVendorServiceDetail(vendorServiceId, unavailable, metadata));
  }

  public Optional<VendorDetailResponse> getVendorDetail(
      String orgId, String vendorId, OutboundAuth auth) {
    List<ProductOutcome> outcomes = coordinator.fetchAll(orgId, auth);
    ResponseMetadataDto metadata = metadata(outcomes);
    List<UnavailableProductDto> unavailable = unavailableProducts(outcomes);
    Optional<VendorScopedView> projection =
        aggregator.toVendorScopedView(vendorId, contributing(outcomes));

    if (projection.isPresent()) {
      return Optional.of(toVendorDetailResponse(projection.get(), unavailable, metadata));
    }
    if (unavailable.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(emptyVendorDetail(vendorId, unavailable, metadata));
  }

  // ----- detail assembly -----

  private VendorServiceDetailResponse toVendorServiceDetailResponse(
      VendorServiceDetail d,
      List<UnavailableProductDto> unavailable,
      ResponseMetadataDto metadata) {
    List<DataSourceDto> dataSources = new ArrayList<>(d.dataSources().size());
    for (DataSourceDetail ds : d.dataSources()) {
      List<IntegrationDto> integrations = new ArrayList<>(ds.integrations().size());
      for (IntegrationDetail in : ds.integrations()) {
        integrations.add(
            new IntegrationDto(
                in.integrationId(),
                in.integrationLabel(),
                healthOf(in.status()),
                in.lastSuccessTimestamp(),
                in.configurationUrl()));
      }
      dataSources.add(
          new DataSourceDto(
              ds.dataSourceId(),
              ds.displayName(),
              ds.integrationType(),
              ds.productName(),
              healthOf(ds.status()),
              ds.integrationsCount(),
              integrations));
    }
    return new VendorServiceDetailResponse(
        d.vendorServiceId(),
        d.vendorServiceName(),
        d.vendorId(),
        d.vendorName(),
        aggregator.wireCategoryOf(d),
        healthOf(d.aggregateHealth()),
        lastUpdatedOr(d.lastUpdated(), metadata.asOf()),
        dataSources,
        unavailable,
        metadata);
  }

  private VendorServiceDetailResponse emptyVendorServiceDetail(
      String vendorServiceId,
      List<UnavailableProductDto> unavailable,
      ResponseMetadataDto metadata) {
    return new VendorServiceDetailResponse(
        vendorServiceId,
        vendorServiceId,
        UNKNOWN_ID,
        UNKNOWN_NAME,
        WIRE_CATEGORY_OTHER,
        HealthState.MISSING_DATA,
        metadata.asOf(),
        List.of(),
        unavailable,
        metadata);
  }

  private VendorDetailResponse toVendorDetailResponse(
      VendorScopedView v, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    List<VendorServiceCardNestedDto> nested = new ArrayList<>(v.vendorServices().size());
    for (VendorServiceCard card : v.vendorServices()) {
      nested.add(toNestedCardDto(card, metadata.asOf()));
    }
    return new VendorDetailResponse(
        v.vendorId(),
        v.vendorName(),
        healthOf(v.aggregateHealth()),
        lastUpdatedOr(v.lastUpdated(), metadata.asOf()),
        nested,
        unavailable,
        metadata);
  }

  private VendorServiceCardNestedDto toNestedCardDto(VendorServiceCard card, Instant asOf) {
    return new VendorServiceCardNestedDto(
        card.vendorServiceId(),
        card.vendorServiceName(),
        aggregator.wireCategoryOf(card),
        card.integrationsConnected(),
        typeCountDtos(card),
        card.productsConnected(),
        healthOf(card.aggregateHealth()),
        lastUpdatedOr(card.lastUpdated(), asOf));
  }

  private VendorDetailResponse emptyVendorDetail(
      String vendorId, List<UnavailableProductDto> unavailable, ResponseMetadataDto metadata) {
    return new VendorDetailResponse(
        vendorId,
        UNKNOWN_NAME,
        HealthState.MISSING_DATA,
        metadata.asOf(),
        List.of(),
        unavailable,
        metadata);
  }

  private VendorServiceCardDto toCardDto(VendorServiceCard card, Instant asOf) {
    return new VendorServiceCardDto(
        card.vendorServiceId(),
        card.vendorServiceName(),
        card.vendorId(),
        card.vendorName(),
        aggregator.wireCategoryOf(card),
        card.integrationsConnected(),
        typeCountDtos(card),
        card.productsConnected(),
        healthOf(card.aggregateHealth()),
        lastUpdatedOr(card.lastUpdated(), asOf));
  }
}
