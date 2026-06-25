package com.rapid7.integrationregistry.adapter.insightidr;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * InsightIDR adapter. Implements the RFC-001 §InsightIDRAdapter two-call pattern: a {@code
 * /eventsources/search} call returns a lightweight list (no health), then one {@code
 * /eventsources/{id}} detail call per source supplies health. Detail calls are parallelised via a
 * bounded {@link BoundedDetailFetcher}, so the N+1 cost is contained inside the adapter and never
 * escapes to the T07 coordinator.
 *
 * <p>The HTTP transport (and its {@code RestClient} exception mapping) lives in {@link
 * EventSourceClient}; this adapter owns orchestration, status normalization, source-identifier
 * resolution, and {@code configuration_url} templating. Per ADR-002 the calls are blocking. The
 * adapter emits the raw {@code (source_type, source_value)} identifier only — vendor resolution is
 * the aggregator's job (T08).
 */
// CouplingBetweenObjects: orchestrating the two-call pattern legitimately touches the contract
// types (FetchResult, NormalizedIntegration, SourceIdentifier, IntegrationStatus), all three
// adapter exceptions (each mapped to a distinct outcome — auth aborts the fetch, transient maps a
// source to missing_data), its four collaborators (EventSourceClient, EventSourceStatusMapper,
// BoundedDetailFetcher, InsightIDRProperties), and the injected Clock — pushing CBO just past the
// project threshold of 15 even after the transport was extracted to EventSourceClient. The HTTP
// surface already lives in a separate class; splitting orchestration further would scatter cohesive
// logic to satisfy a metric. Suppressed locally and justified rather than weakening the
// project-wide
// threshold, mirroring the precedent on FanOutCoordinator and VendorAggregator (ADR-003).
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
public class InsightIDRAdapter implements IntegrationAdapter {

  private static final Logger log = LoggerFactory.getLogger(InsightIDRAdapter.class);

  static final String PRODUCT_NAME = "InsightIDR";
  static final String INTEGRATION_TYPE = "SIEM Event Source";
  static final String SOURCE_TYPE_PRODUCT_TYPE = "product_type";
  static final String SOURCE_TYPE_PRODUCT_NAME = "product_name";

  private final EventSourceClient client;
  private final EventSourceStatusMapper statusMapper;
  private final BoundedDetailFetcher detailFetcher;
  private final InsightIDRProperties properties;
  private final Clock clock;

  /**
   * Spring's injection path: the clock defaults to {@link Clock#systemUTC()}. Staleness is computed
   * against this clock, not a hard-coded {@code Instant.now()}, so it stays testable.
   * {@code @Autowired} marks this as the constructor Spring uses — without it, the second
   * (clock-injecting) constructor makes the bean ambiguous and Spring falls back to a non-existent
   * no-arg constructor.
   */
  @Autowired
  public InsightIDRAdapter(
      EventSourceClient client,
      EventSourceStatusMapper statusMapper,
      BoundedDetailFetcher detailFetcher,
      InsightIDRProperties properties) {
    this(client, statusMapper, detailFetcher, properties, Clock.systemUTC());
  }

  /**
   * Test seam: inject a fixed {@link Clock} so staleness against pinned fixtures is deterministic.
   */
  InsightIDRAdapter(
      EventSourceClient client,
      EventSourceStatusMapper statusMapper,
      BoundedDetailFetcher detailFetcher,
      InsightIDRProperties properties,
      Clock clock) {
    this.client = client;
    this.statusMapper = statusMapper;
    this.detailFetcher = detailFetcher;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public String productName() {
    return PRODUCT_NAME;
  }

  @Override
  public FetchResult fetch(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    Instant fetchedAt = Instant.now(clock);
    List<EventSourceSearchDto> searchResults = client.search(orgId, authHeaders);

    List<EventSourceSearchDto> usable =
        searchResults.stream().filter(this::isNormalizable).toList();

    try {
      List<NormalizedIntegration> integrations =
          detailFetcher.fetchAll(
              usable,
              properties.detailConcurrency(),
              src -> normalize(src, orgId, authHeaders, fetchedAt));
      return new FetchResult(integrations, fetchedAt);
    } catch (SystemicAuthException e) {
      // A detail call returned 401/403 — bad credentials fail every call, so the whole fetch
      // is unavailable. BoundedDetailFetcher takes a Function (no checked throws), so the
      // checked AdapterAuthException was carried out as unchecked; unwrap it here.
      throw e.unwrap();
    }
  }

  private boolean isNormalizable(EventSourceSearchDto src) {
    if (!StringUtils.hasText(src.id())) {
      log.warn("Skipping InsightIDR event source with missing id (name='{}')", src.name());
      return false;
    }
    return true;
  }

  /**
   * Normalize one event source. Fetches its detail; a per-source timeout/5xx maps the source to
   * {@code missing_data} (the source stays visible, the IDR view stays available). An auth failure
   * is systemic and is rethrown (wrapped unchecked) to abort the whole fetch.
   */
  private NormalizedIntegration normalize(
      EventSourceSearchDto src, String orgId, HttpHeaders authHeaders, Instant fetchedAt) {
    SourceIdentifier sourceId = resolveSourceIdentifier(src);
    IntegrationStatus status;
    Instant lastSuccess;
    String apiReturnedUrl = null;
    try {
      EventSourceDetailsDto detail = client.detail(orgId, src.id(), authHeaders);
      apiReturnedUrl = detail.configurationUrl();
      status =
          statusMapper.deriveStatus(
              detail.status(),
              detail.issue(),
              detail.lastActive(),
              fetchedAt,
              properties.stalenessThreshold());
      lastSuccess = statusMapper.deriveLastSuccess(detail.lastActive());
    } catch (AdapterAuthException e) {
      // Auth is systemic: rethrow unchecked so BoundedDetailFetcher propagates it and the
      // adapter's fetch() unwraps it to the checked AdapterAuthException it declares.
      throw new SystemicAuthException(e);
    } catch (AdapterTimeoutException | AdapterUpstreamException e) {
      // One source's detail call failed transiently: surface it as missing_data (the source
      // stays visible, the IDR product view stays available) rather than failing the whole fetch.
      log.warn(
          "InsightIDR detail fetch failed for event source '{}' ({}); mapping to missing_data",
          src.id(),
          e.reasonCode());
      status = IntegrationStatus.MISSING_DATA;
      lastSuccess = null;
    }
    return new NormalizedIntegration(
        src.id(),
        sourceId,
        PRODUCT_NAME,
        INTEGRATION_TYPE,
        src.name(),
        status,
        lastSuccess,
        configurationUrl(src.id(), apiReturnedUrl),
        orgId);
  }

  private SourceIdentifier resolveSourceIdentifier(EventSourceSearchDto src) {
    if (StringUtils.hasText(src.productType())) {
      return new SourceIdentifier(SOURCE_TYPE_PRODUCT_TYPE, src.productType());
    }
    log.warn(
        "InsightIDR event source '{}' (name='{}') has no productType; "
            + "falling back to product_name='{}' — curator should correct the source",
        src.id(),
        src.name(),
        src.productName());
    return new SourceIdentifier(SOURCE_TYPE_PRODUCT_NAME, src.productName());
  }

  private String configurationUrl(String id, String apiReturnedUrl) {
    if (StringUtils.hasText(apiReturnedUrl)) {
      return apiReturnedUrl;
    }
    return properties.idrBase() + "/eventsources/" + id;
  }

  /** Unchecked carrier so a systemic auth failure escapes BoundedDetailFetcher and is unwrapped. */
  private static final class SystemicAuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SystemicAuthException(AdapterAuthException cause) {
      super(cause);
    }

    AdapterAuthException unwrap() {
      return (AdapterAuthException) getCause();
    }
  }
}
