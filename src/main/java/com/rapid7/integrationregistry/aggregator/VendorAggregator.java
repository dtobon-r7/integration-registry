package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.aggregator.projection.DataSourceDetail;
import com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail;
import com.rapid7.integrationregistry.aggregator.projection.IntegrationTypeCount;
import com.rapid7.integrationregistry.aggregator.projection.VendorCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorScopedView;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard;
import com.rapid7.integrationregistry.aggregator.projection.VendorServiceDetail;
import com.rapid7.integrationregistry.mapping.DataSourceResolution;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 *
 * <p><b>Thread safety:</b> safe for concurrent invocation by multiple coordinator threads on the
 * same singleton instance — the {@code snapshot} field is final and immutable, and all per-call
 * WARN-dedup state ({@code Set<TripletKey>} for unmapped triplets, {@code Set<String>} for
 * bundle-integrity conflicts) lives on the stack. Adding any instance field beyond {@code snapshot}
 * would require revisiting this contract.
 *
 * <p><b>Adapter wire-form expectations:</b> each {@link NormalizedIntegration} is expected to carry
 * {@code productName} and {@code sourceIdentifier.sourceType} in their canonical wire-form per
 * {@link com.rapid7.integrationregistry.adapter.IntegrationAdapter#productName()} and {@link
 * SourceType#wireForm()}. The unmapped-triplet WARN dedup keys on the raw strings, so casing or
 * whitespace drift between adapters would be treated as distinct triplets and produce duplicate
 * WARNs.
 *
 * <p><b>Unmappable-enum branch log semantics:</b> when {@link ProductName#fromWireForm} or {@link
 * SourceType#fromWireForm} returns empty, the snapshot is never consulted — the WARN's {@code
 * mappingVersion} field is therefore informational (it's the version that <em>would</em> have been
 * queried), not diagnostic of a bundle gap.
 */
// VendorAggregator is the projection hub: it touches NormalizedIntegration plus all four
// projection records and the resolution support types. The 17-type coupling is structural
// to its role per RFC-001 §Component Design and not reducible without splitting the public
// surface across multiple components, which the RFC explicitly rejects. The method count
// is structural for the same reason: each public projection has its own grouping +
// rollup helpers, and splitting them across classes would fracture the projection hub.
// GodClass fires for the same structural reason: WMC accumulates across the four projection
// pipelines (resolution + four projections + bundle-integrity guards), and ATFD reflects
// the projection-hub coupling we already accept.
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods", "PMD.GodClass"})
@Component
public final class VendorAggregator {

  private static final Logger LOG = LoggerFactory.getLogger(VendorAggregator.class);

  private static final String UNMAPPED_LOG_FORMAT =
      "Unmapped vendor mapping triplet: productName='{}' sourceType='{}' "
          + "sourceValue='{}' mappingVersion='{}'";

  private static final String VS_IDENTITY_CONFLICT_LOG_FORMAT =
      "Bundle integrity warning: vendor_service_id='{}' has inconsistent identity "
          + "across resolved instances — vendor_service_name='{}' vs '{}', "
          + "vendor_id='{}' vs '{}', vendor_name='{}' vs '{}', "
          + "vendor_category='{}' vs '{}', mappingVersion='{}'";

  private static final String VENDOR_NAME_CONFLICT_LOG_FORMAT =
      "Bundle integrity warning: vendor_id='{}' has inconsistent vendor_name "
          + "across resolved instances — '{}' vs '{}', mappingVersion='{}'";

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
    Set<String> warnedConflicts = new HashSet<>();
    return buildVendorServiceCards(resolved, warnedConflicts);
  }

  public List<VendorCard> toVendorCards(List<NormalizedIntegration> instances) {
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    if (instances.isEmpty()) {
      return List.of();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    Set<String> warnedConflicts = new HashSet<>();
    warnIfVendorCardsHaveNameInconsistencies(resolved, warnedConflicts);
    return buildVendorCards(resolved);
  }

  public Optional<VendorScopedView> toVendorScopedView(
      String vendorId, List<NormalizedIntegration> instances) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(instances, FIELD_INSTANCES);
    if (instances.isEmpty()) {
      return Optional.empty();
    }
    List<ResolvedInstance> resolved = resolveAll(instances);
    List<ResolvedInstance> scoped =
        resolved.stream().filter(r -> vendorId.equals(r.resolution().vendorId())).toList();
    if (scoped.isEmpty()) {
      return Optional.empty();
    }
    Set<String> warnedConflicts = new HashSet<>();
    return Optional.of(buildVendorScopedView(scoped, warnedConflicts));
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
    Set<String> warnedConflicts = new HashSet<>();
    return Optional.of(buildVendorServiceDetail(scoped, warnedConflicts));
  }

  // ----- T09 pass-throughs -----

  /**
   * The {@code metadata.mapping_version} of the loaded bundle, surfaced for T09's response assembly
   * so the service layer never has to depend on {@code ..mapping..} (ArchUnit-forbidden).
   */
  public String mappingVersion() {
    return snapshot.mappingVersion();
  }

  /**
   * Translate a projection's internal {@link VendorCategory} to a contract-valid openapi
   * VendorCategory wire value. Exists so the service layer can populate the wire DTO without naming
   * {@link VendorCategory} (ArchUnit-forbidden). The internal enum and the wire enum have
   * mismatched value sets; non-overlapping internal values fold to the wire's {@code other}
   * fallback, except {@code cloud_provider} which maps to the wire's {@code cloud}.
   */
  public String wireCategoryOf(VendorServiceCard card) {
    return toWireCategory(card.vendorCategory());
  }

  /** See {@link #wireCategoryOf(VendorServiceCard)}. */
  public String wireCategoryOf(VendorServiceDetail detail) {
    return toWireCategory(detail.vendorCategory());
  }

  private static String toWireCategory(VendorCategory category) {
    return switch (category) {
      case EDR -> "edr";
      case SIEM -> "siem";
      case ITSM -> "itsm";
      case CLOUD_PROVIDER -> "cloud";
      case IDENTITY, NOTIFICATION, OTHER -> "other";
    };
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
      DataSourceResolution resolution =
          snapshot.lookup(productEnum.get(), sourceTypeEnum.get(), sourceValue);
      String dataSourceId =
          DataSourceIdMinter.mint(rawProductName, sourceTypeEnum.get(), sourceValue);
      if (Objects.equals(resolution.identity(), VendorResolution.unknown())) {
        warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
      }
      // displayName is the curated, data-source-level bundle label (or "Unknown" for unmapped
      // triplets via DataSourceResolution.unknown()) — never the raw sourceValue.
      return new ResolvedInstance(n, dataSourceId, resolution.identity(), resolution.displayName());
    }

    // Unmappable enum strings — route through the same unknown path. displayName is the fixed
    // "Unknown" label (DataSourceResolution.unknown()), never the raw sourceValue.
    DataSourceResolution resolution = DataSourceResolution.unknown();
    String dataSourceId = DataSourceIdMinter.mint(rawProductName, rawSourceType, sourceValue);
    warnOnceForTriplet(rawProductName, rawSourceType, sourceValue, warned);
    return new ResolvedInstance(n, dataSourceId, resolution.identity(), resolution.displayName());
  }

  private void warnOnceForTriplet(
      String productName, String sourceType, String sourceValue, Set<TripletKey> warned) {
    if (warned.add(new TripletKey(productName, sourceType, sourceValue))) {
      LOG.warn(
          UNMAPPED_LOG_FORMAT, productName, sourceType, sourceValue, snapshot.mappingVersion());
    }
  }

  // ----- vendor-service cards -----

  private List<VendorServiceCard> buildVendorServiceCards(
      List<ResolvedInstance> resolved, Set<String> warnedConflicts) {
    Map<String, List<ResolvedInstance>> byVendorServiceId = new LinkedHashMap<>();
    for (ResolvedInstance r : resolved) {
      byVendorServiceId
          .computeIfAbsent(r.resolution().vendorServiceId(), k -> new ArrayList<>())
          .add(r);
    }
    List<VendorServiceCard> cards = new ArrayList<>(byVendorServiceId.size());
    for (Map.Entry<String, List<ResolvedInstance>> e : byVendorServiceId.entrySet()) {
      cards.add(buildVendorServiceCard(e.getValue(), warnedConflicts));
    }
    return cards;
  }

  private VendorServiceCard buildVendorServiceCard(
      List<ResolvedInstance> group, Set<String> warnedConflicts) {
    VendorResolution r = group.get(0).resolution();
    warnIfVendorServiceIdentityInconsistent(group, r, warnedConflicts);
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

  private void warnIfVendorServiceIdentityInconsistent(
      List<ResolvedInstance> group, VendorResolution canonical, Set<String> warnedConflicts) {
    for (int i = 1; i < group.size(); i++) {
      VendorResolution other = group.get(i).resolution();
      if (!Objects.equals(canonical, other)) {
        String dedupKey = "vs:" + canonical.vendorServiceId();
        if (warnedConflicts.add(dedupKey)) {
          LOG.warn(
              VS_IDENTITY_CONFLICT_LOG_FORMAT,
              canonical.vendorServiceId(),
              canonical.vendorServiceName(),
              other.vendorServiceName(),
              canonical.vendorId(),
              other.vendorId(),
              canonical.vendorName(),
              other.vendorName(),
              canonical.vendorCategory(),
              other.vendorCategory(),
              snapshot.mappingVersion());
        }
        return;
      }
    }
  }

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

  private VendorServiceDetail buildVendorServiceDetail(
      List<ResolvedInstance> group, Set<String> warnedConflicts) {
    VendorResolution r = group.get(0).resolution();
    warnIfVendorServiceIdentityInconsistent(group, r, warnedConflicts);
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

  // ----- vendor-scoped view -----

  private VendorScopedView buildVendorScopedView(
      List<ResolvedInstance> scoped, Set<String> warnedConflicts) {
    VendorResolution v = scoped.get(0).resolution();
    warnIfVendorNameInconsistent(v.vendorId(), v.vendorName(), scoped, warnedConflicts);
    List<VendorServiceCard> services = buildVendorServiceCards(scoped, warnedConflicts);
    IntegrationStatus aggregate =
        services.stream()
            .map(VendorServiceCard::aggregateHealth)
            .reduce(HealthRollup::worstOf)
            .orElseThrow();
    Instant lastUpdated = latestSuccess(scoped);
    return new VendorScopedView(
        v.vendorId(), v.vendorName(), services.size(), aggregate, lastUpdated, services);
  }

  private void warnIfVendorNameInconsistent(
      String vendorId,
      String canonicalName,
      List<ResolvedInstance> scoped,
      Set<String> warnedConflicts) {
    for (int i = 1; i < scoped.size(); i++) {
      String otherName = scoped.get(i).resolution().vendorName();
      if (!canonicalName.equals(otherName)) {
        String dedupKey = "vendor:" + vendorId;
        if (warnedConflicts.add(dedupKey)) {
          LOG.warn(
              VENDOR_NAME_CONFLICT_LOG_FORMAT,
              vendorId,
              canonicalName,
              otherName,
              snapshot.mappingVersion());
        }
        return;
      }
    }
  }

  // ----- vendor cards -----

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

  private void warnIfVendorCardsHaveNameInconsistencies(
      List<ResolvedInstance> resolved, Set<String> warnedConflicts) {
    Map<String, String> firstSeenName = new HashMap<>();
    for (ResolvedInstance r : resolved) {
      VendorResolution res = r.resolution();
      String canonical = firstSeenName.putIfAbsent(res.vendorId(), res.vendorName());
      if (canonical != null && !canonical.equals(res.vendorName())) {
        String dedupKey = "vendor:" + res.vendorId();
        if (warnedConflicts.add(dedupKey)) {
          LOG.warn(
              VENDOR_NAME_CONFLICT_LOG_FORMAT,
              res.vendorId(),
              canonical,
              res.vendorName(),
              snapshot.mappingVersion());
        }
      }
    }
  }

  private static final class VendorAccumulator {
    private final String vendorName;
    private final Set<String> vendorServiceIds = new LinkedHashSet<>();

    private VendorAccumulator(String vendorName) {
      this.vendorName = vendorName;
    }
  }
}
