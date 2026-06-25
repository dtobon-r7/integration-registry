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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * InsightIDR adapter. Implements the RFC-001 §InsightIDRAdapter two-call pattern: a {@code
 * /eventsources/search} call returns a lightweight list (no health), then one {@code
 * /eventsources/{id}} detail call per source supplies health. Detail calls are parallelised via a
 * bounded {@link BoundedDetailFetcher}, so the N+1 cost is contained inside the adapter and never
 * escapes to the T07 coordinator.
 *
 * <p>Per ADR-002 outbound calls use {@link RestClient}. The adapter emits the raw {@code
 * (source_type, source_value)} identifier only — vendor resolution is the aggregator's job (T08).
 */
@Component
public class InsightIDRAdapter implements IntegrationAdapter {

  private static final Logger log = LoggerFactory.getLogger(InsightIDRAdapter.class);

  static final String PRODUCT_NAME = "InsightIDR";
  static final String INTEGRATION_TYPE = "SIEM Event Source";
  static final String SOURCE_TYPE_PRODUCT_TYPE = "product_type";
  static final String SOURCE_TYPE_PRODUCT_NAME = "product_name";

  private static final String SEARCH_PATH =
      "/api/3/organizations/{orgId}/eventsources/search?query=";
  private static final String DETAIL_PATH = "/api/3/organizations/{orgId}/eventsources/{id}";

  private final RestClient restClient;
  private final EventSourceStatusMapper statusMapper;
  private final BoundedDetailFetcher detailFetcher;
  private final InsightIDRProperties properties;
  private final Clock clock;

  /**
   * Spring's injection path: the clock defaults to {@link Clock#systemUTC()}. Staleness is computed
   * against this clock, not a hard-coded {@code Instant.now()}, so it stays testable.
   */
  public InsightIDRAdapter(
      RestClient insightIDRRestClient,
      EventSourceStatusMapper statusMapper,
      BoundedDetailFetcher detailFetcher,
      InsightIDRProperties properties) {
    this(insightIDRRestClient, statusMapper, detailFetcher, properties, Clock.systemUTC());
  }

  /**
   * Test seam: inject a fixed {@link Clock} so staleness against pinned fixtures is deterministic.
   */
  InsightIDRAdapter(
      RestClient insightIDRRestClient,
      EventSourceStatusMapper statusMapper,
      BoundedDetailFetcher detailFetcher,
      InsightIDRProperties properties,
      Clock clock) {
    this.restClient = insightIDRRestClient;
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
    List<EventSourceSearchDto> searchResults = search(orgId, authHeaders);

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

  private List<EventSourceSearchDto> search(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    try {
      EventSourceSearchDto[] body =
          restClient
              .get()
              .uri(SEARCH_PATH, orgId)
              .headers(h -> h.addAll(authHeaders))
              .retrieve()
              .body(EventSourceSearchDto[].class);
      return body == null ? List.of() : List.of(body);
    } catch (HttpClientErrorException e) {
      throwClientError("search", e);
      throw new IllegalStateException("unreachable: throwClientError always throws");
    } catch (HttpServerErrorException e) {
      throw new AdapterUpstreamException("InsightIDR search 5xx: " + e.getStatusCode(), e);
    } catch (ResourceAccessException e) {
      throw new AdapterTimeoutException(
          "InsightIDR search failed (timeout/transport): " + e.getMessage(), e);
    } catch (RestClientException e) {
      throw new AdapterUpstreamException(
          "InsightIDR search response could not be processed: " + e.getMessage(), e);
    }
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
      EventSourceDetailsDto detail = detail(orgId, src.id(), authHeaders);
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

  private EventSourceDetailsDto detail(String orgId, String id, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    try {
      return restClient
          .get()
          .uri(DETAIL_PATH, orgId, id)
          .headers(h -> h.addAll(authHeaders))
          .retrieve()
          .body(EventSourceDetailsDto.class);
    } catch (HttpClientErrorException e) {
      throwClientError("detail", e);
      throw new IllegalStateException("unreachable: throwClientError always throws");
    } catch (HttpServerErrorException e) {
      throw new AdapterUpstreamException("InsightIDR detail 5xx: " + e.getStatusCode(), e);
    } catch (ResourceAccessException e) {
      throw new AdapterTimeoutException(
          "InsightIDR detail failed (timeout/transport): " + e.getMessage(), e);
    } catch (RestClientException e) {
      throw new AdapterUpstreamException(
          "InsightIDR detail response could not be processed: " + e.getMessage(), e);
    }
  }

  /**
   * Map a 4xx to the matching adapter exception, preserving the cause: 401/403 are auth failures;
   * every other 4xx is upstream-broken (the contract exposes no distinct 4xx signal). Always throws
   * — mirrors {@code InsightConnectAdapter.throwClientError}. {@code phase} is "search" or "detail"
   * for diagnostics.
   */
  private static void throwClientError(String phase, HttpClientErrorException e)
      throws AdapterAuthException, AdapterUpstreamException {
    int code = e.getStatusCode().value();
    if (code == 401 || code == 403) {
      throw new AdapterAuthException(
          "InsightIDR " + phase + " auth failure: " + e.getStatusCode(), e);
    }
    throw new AdapterUpstreamException("InsightIDR " + phase + " 4xx: " + e.getStatusCode(), e);
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
