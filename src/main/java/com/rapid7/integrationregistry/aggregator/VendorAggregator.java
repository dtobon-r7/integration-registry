package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stateless component that turns a flat list of {@link NormalizedIntegration} (from the fan-out
 * coordinator) plus the {@link VendorMappingSnapshot} into the four read-API projection records —
 * vendor-service cards, vendor cards, vendor-scoped views, and vendor-service details. See RFC-001
 * §Component Design → VendorAggregator.
 *
 * <p>Every public method runs the same private resolution pass first, then fans out into
 * projection-specific grouping. Resolution is a single-pass walk that converts each instance's raw
 * {@code (productName, sourceType, sourceValue)} triplet into a {@link ResolvedInstance} carrying
 * the minted {@code data_source_id}, the canonical {@link VendorResolution}, and the
 * per-data-source {@code displayName}. Unmapped triplets — including those where the raw strings
 * don't even resolve to {@link ProductName} or {@link SourceType} enum members — fold into {@link
 * VendorResolution#unknown()} and emit a single WARN per distinct triplet per call.
 */
// VendorAggregator is the projection hub: it touches NormalizedIntegration plus all four
// projection records and the resolution support types. The 17-type coupling is structural
// to its role per RFC-001 §Component Design and not reducible without splitting the public
// surface across multiple components, which the RFC explicitly rejects.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
public final class VendorAggregator {

  private static final Logger LOG = LoggerFactory.getLogger(VendorAggregator.class);

  private static final String UNMAPPED_LOG_FORMAT =
      "Unmapped vendor mapping triplet: productName='{}' sourceType='{}' "
          + "sourceValue='{}' mappingVersion='{}'";

  private static final String FIELD_INSTANCES = "instances";

  private final VendorMappingSnapshot snapshot;

  public VendorAggregator(VendorMappingSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  public List<VendorServiceCard> toVendorServiceCards(List<NormalizedIntegration> instances) {
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    if (instances.isEmpty()) {
      return List.of();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    return buildVendorServiceCards(resolved);
  }

  public List<VendorCard> toVendorCards(List<NormalizedIntegration> instances) {
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    // Implemented in a later task. Returning an empty list keeps the public
    // surface compilable for tests that only exercise toVendorServiceCards.
    if (instances.isEmpty()) {
      return List.of();
    }
    return List.of();
  }

  public Optional<VendorScopedView> toVendorScopedView(
      String vendorId, List<NormalizedIntegration> instances) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    // Implemented in a later task.
    return Optional.empty();
  }

  public Optional<VendorServiceDetail> toVendorServiceDetail(
      String vendorServiceId, List<NormalizedIntegration> instances) {
    Objects.requireNonNull(vendorServiceId, "vendorServiceId");
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    // Implemented in a later task.
    return Optional.empty();
  }

  // ----- resolution pass -----

  private record TripletKey(String productName, String sourceType, String sourceValue) {}

  private List<ResolvedInstance> resolveAll(List<NormalizedIntegration> instances) {
    Set<TripletKey> warned = new HashSet<>();
    List<ResolvedInstance> resolved = new ArrayList<>(instances.size());
    for (NormalizedIntegration n : instances) {
      Objects.requireNonNull(n, "instance");
      resolved.add(resolveOne(n, warned));
    }
    return resolved;
  }

  private ResolvedInstance resolveOne(NormalizedIntegration n, Set<TripletKey> warned) {
    String rawProductName = n.productName();
    String rawSourceType = n.sourceIdentifier().sourceType();
    String sourceValue = n.sourceIdentifier().sourceValue();

    Optional<ProductName> productEnum = ProductName.fromWireForm(rawProductName);
    Optional<SourceType> sourceTypeEnum = SourceType.fromWireForm(rawSourceType);

    // Both DataSourceIdMinter overloads produce identical output for the same wire-form
    // triplet (enforced by DataSourceIdMinterStringOverloadTest's parity assertion).
    // The two branches below mint via the enum overload (when fromWireForm resolves) and
    // the String overload (when it doesn't); the resulting dataSourceId is byte-identical.
    if (productEnum.isPresent() && sourceTypeEnum.isPresent()) {
      VendorResolution resolution =
          snapshot.lookup(productEnum.get(), sourceTypeEnum.get(), sourceValue);
      String dataSourceId =
          DataSourceIdMinter.mint(rawProductName, sourceTypeEnum.get(), sourceValue);
      if (Objects.equals(resolution, VendorResolution.unknown())) {
        warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
      }
      // displayName == sourceValue is intentional per spec §displayName gap (deferred-scope ruling)
      return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
    }

    // Unmappable enum strings — route through the same unknown path.
    VendorResolution resolution = VendorResolution.unknown();
    String dataSourceId = DataSourceIdMinter.mint(rawProductName, rawSourceType, sourceValue);
    warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
    // displayName == sourceValue is intentional per spec §displayName gap (deferred-scope ruling)
    return new ResolvedInstance(n, dataSourceId, resolution, sourceValue);
  }

  private void warnOnceForTriplet(
      String productName, String sourceType, String sourceValue, Set<TripletKey> warned) {
    if (warned.add(new TripletKey(productName, sourceType, sourceValue))) {
      LOG.warn(
          UNMAPPED_LOG_FORMAT, productName, sourceType, sourceValue, snapshot.mappingVersion());
    }
  }

  // ----- vendor-service cards -----

  // Per-vendor-service buckets are minted lazily via computeIfAbsent — one ArrayList per
  // distinct vendorServiceId, never per iteration. Hoisting the allocation outside the loop
  // would defeat the grouping.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private List<VendorServiceCard> buildVendorServiceCards(List<ResolvedInstance> resolved) {
    Map<String, List<ResolvedInstance>> byVendorServiceId = new LinkedHashMap<>();
    for (ResolvedInstance r : resolved) {
      byVendorServiceId
          .computeIfAbsent(r.resolution().vendorServiceId(), k -> new ArrayList<>())
          .add(r);
    }
    List<VendorServiceCard> cards = new ArrayList<>(byVendorServiceId.size());
    for (Map.Entry<String, List<ResolvedInstance>> e : byVendorServiceId.entrySet()) {
      cards.add(buildVendorServiceCard(e.getValue()));
    }
    return cards;
  }

  private VendorServiceCard buildVendorServiceCard(List<ResolvedInstance> group) {
    ResolvedInstance first = group.get(0);
    VendorResolution r = first.resolution();
    // Aggregates and rollup land in later tasks. For now: simple instance
    // count, healthy-by-default rollup is wrong but is replaced before it
    // ships to PR — Task 7's mixed-state DS test forces this code path to
    // use HealthRollup correctly.
    int integrationsConnected = group.size();
    return new VendorServiceCard(
        r.vendorServiceId(),
        r.vendorServiceName(),
        r.vendorId(),
        r.vendorName(),
        r.vendorCategory(),
        integrationsConnected,
        List.of(), // integrationTypeCounts — Task 9
        List.of(), // productsConnected — Task 9
        group.get(0).instance().status(), // aggregateHealth — Task 7 makes this correct
        null); // lastUpdated — Task 9
  }
}
