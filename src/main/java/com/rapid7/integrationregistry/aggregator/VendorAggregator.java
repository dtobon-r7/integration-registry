package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
// surface across multiple components, which the RFC explicitly rejects. The method count
// is structural for the same reason: each public projection has its own grouping +
// rollup helpers, and splitting them across classes would fracture the projection hub.
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@Component
public final class VendorAggregator {

  private static final Logger LOG = LoggerFactory.getLogger(VendorAggregator.class);

  private static final String UNMAPPED_LOG_FORMAT =
      "Unmapped vendor mapping triplet: productName='{}' sourceType='{}' "
          + "sourceValue='{}' mappingVersion='{}'";

  private static final String FIELD_INSTANCES = "instances";

  private static final String SUPPRESS_LAZY_BUCKET_ALLOC = "PMD.AvoidInstantiatingObjectsInLoops";

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
    if (instances.isEmpty()) {
      return List.of();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    return buildVendorCards(resolved);
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
    if (instances.isEmpty()) {
      return Optional.empty();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    List<ResolvedInstance> scoped =
        resolved.stream()
            .filter(r -> vendorServiceId.equals(r.resolution().vendorServiceId()))
            .toList();
    if (scoped.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(buildVendorServiceDetail(scoped));
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
  @SuppressWarnings(SUPPRESS_LAZY_BUCKET_ALLOC)
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
    VendorResolution r = group.get(0).resolution();
    Map<String, List<ResolvedInstance>> byDataSourceId = groupByDataSourceId(group);
    IntegrationStatus aggregate =
        byDataSourceId.values().stream()
            .map(VendorAggregator::dataSourceStatus)
            .reduce(HealthRollup::worstOf)
            .orElseThrow();
    return new VendorServiceCard(
        r.vendorServiceId(),
        r.vendorServiceName(),
        r.vendorId(),
        r.vendorName(),
        r.vendorCategory(),
        group.size(),
        integrationTypeCounts(group),
        productsConnected(group),
        aggregate,
        latestSuccess(group));
  }

  // computeIfAbsent mints one int[2] counter array per distinct integrationType — never per
  // iteration. Hoisting the allocation outside the loop would defeat the grouping; the same
  // pattern is used in groupByDataSourceId and buildVendorServiceCards above.
  @SuppressWarnings(SUPPRESS_LAZY_BUCKET_ALLOC)
  private static List<IntegrationTypeCount> integrationTypeCounts(List<ResolvedInstance> group) {
    Map<String, int[]> totals = new LinkedHashMap<>();
    for (ResolvedInstance r : group) {
      String type = r.instance().integrationType();
      int[] counts = totals.computeIfAbsent(type, k -> new int[2]);
      counts[0]++;
      if (r.instance().status() == IntegrationStatus.ERROR) {
        counts[1]++;
      }
    }
    List<IntegrationTypeCount> out = new ArrayList<>(totals.size());
    for (Map.Entry<String, int[]> e : totals.entrySet()) {
      out.add(new IntegrationTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    return out;
  }

  private static List<String> productsConnected(List<ResolvedInstance> group) {
    Set<String> seen = new LinkedHashSet<>();
    for (ResolvedInstance r : group) {
      seen.add(r.instance().productName());
    }
    return List.copyOf(seen);
  }

  private static Instant latestSuccess(List<ResolvedInstance> group) {
    Instant max = null;
    for (ResolvedInstance r : group) {
      Instant t = r.instance().lastSuccessTimestamp();
      if (t != null && (max == null || t.isAfter(max))) {
        max = t;
      }
    }
    return max;
  }

  // Per-data-source buckets are minted lazily via computeIfAbsent — one ArrayList per
  // distinct dataSourceId, never per iteration. Hoisting the allocation outside the loop
  // would defeat the grouping.
  @SuppressWarnings(SUPPRESS_LAZY_BUCKET_ALLOC)
  private static Map<String, List<ResolvedInstance>> groupByDataSourceId(
      List<ResolvedInstance> group) {
    Map<String, List<ResolvedInstance>> by = new LinkedHashMap<>();
    for (ResolvedInstance r : group) {
      by.computeIfAbsent(r.dataSourceId(), k -> new ArrayList<>()).add(r);
    }
    return by;
  }

  private static IntegrationStatus dataSourceStatus(List<ResolvedInstance> dsGroup) {
    return dsGroup.stream()
        .map(r -> r.instance().status())
        .reduce(HealthRollup::worstOf)
        .orElseThrow();
  }

  // ----- vendor-service detail -----

  private VendorServiceDetail buildVendorServiceDetail(List<ResolvedInstance> group) {
    VendorResolution r = group.get(0).resolution();
    Map<String, List<ResolvedInstance>> byDataSourceId = groupByDataSourceId(group);
    List<DataSourceDetail> dataSources = new ArrayList<>(byDataSourceId.size());
    for (List<ResolvedInstance> dsGroup : byDataSourceId.values()) {
      dataSources.add(buildDataSourceDetail(dsGroup));
    }
    IntegrationStatus aggregate =
        dataSources.stream()
            .map(DataSourceDetail::status)
            .reduce(HealthRollup::worstOf)
            .orElseThrow();
    return new VendorServiceDetail(
        r.vendorServiceId(),
        r.vendorServiceName(),
        r.vendorId(),
        r.vendorName(),
        r.vendorCategory(),
        group.size(),
        integrationTypeCounts(group),
        productsConnected(group),
        aggregate,
        latestSuccess(group),
        dataSources);
  }

  // Per-element projection record allocation, not a lazy bucket — same fundamental pattern as
  // the lazy-bucket sites: per-element record minting that cannot be hoisted out of the loop
  // without changing the projection semantics. Reuses the existing constant for symmetry with
  // the four other suppression sites in this class.
  @SuppressWarnings(SUPPRESS_LAZY_BUCKET_ALLOC)
  private static DataSourceDetail buildDataSourceDetail(List<ResolvedInstance> dsGroup) {
    ResolvedInstance first = dsGroup.get(0);
    NormalizedIntegration firstInstance = first.instance();
    IntegrationStatus status = dataSourceStatus(dsGroup);
    List<IntegrationDetail> integrations = new ArrayList<>(dsGroup.size());
    for (ResolvedInstance r : dsGroup) {
      NormalizedIntegration n = r.instance();
      integrations.add(
          new IntegrationDetail(
              n.integrationId(),
              r.dataSourceId(),
              n.integrationLabel(),
              n.status(),
              n.lastSuccessTimestamp(),
              n.configurationUrl()));
    }
    return new DataSourceDetail(
        first.dataSourceId(),
        first.displayName(),
        firstInstance.integrationType(),
        firstInstance.productName(),
        status,
        integrations.size(),
        integrations);
  }

  // ----- vendor cards -----

  // Per-vendor accumulators are minted lazily via computeIfAbsent — one VendorAccumulator per
  // distinct vendorId, never per iteration. Hoisting the allocation outside the loop would
  // defeat the grouping; the same pattern is used in groupByDataSourceId and
  // integrationTypeCounts above.
  @SuppressWarnings(SUPPRESS_LAZY_BUCKET_ALLOC)
  private static List<VendorCard> buildVendorCards(List<ResolvedInstance> resolved) {
    // vendorId -> (vendorName, set-of-vendorServiceIds)
    Map<String, VendorAccumulator> byVendorId = new LinkedHashMap<>();
    for (ResolvedInstance r : resolved) {
      VendorResolution res = r.resolution();
      VendorAccumulator acc =
          byVendorId.computeIfAbsent(res.vendorId(), k -> new VendorAccumulator(res.vendorName()));
      acc.vendorServiceIds.add(res.vendorServiceId());
    }
    List<VendorCard> cards = new ArrayList<>(byVendorId.size());
    for (Map.Entry<String, VendorAccumulator> e : byVendorId.entrySet()) {
      cards.add(
          new VendorCard(
              e.getKey(), e.getValue().vendorName, e.getValue().vendorServiceIds.size()));
    }
    return cards;
  }

  private static final class VendorAccumulator {
    private final String vendorName;
    private final Set<String> vendorServiceIds = new LinkedHashSet<>();

    private VendorAccumulator(String vendorName) {
      this.vendorName = vendorName;
    }
  }
}
