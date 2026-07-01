package com.rapid7.integrationregistry.adapter.insightidr;

import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The HTTP boundary for the InsightIDR event-sources API. Owns the two {@code RestClient} calls of
 * the RFC-001 §InsightIDRAdapter two-call pattern — search (list) and per-id detail — and maps the
 * {@code RestClient} failure modes onto the adapter exception family ({@link
 * AdapterTimeoutException} / {@link AdapterAuthException} / {@link AdapterUpstreamException}).
 * Isolating the transport here keeps {@link InsightIDRAdapter} focused on orchestration and
 * normalization (and well under the coupling budget), and confines the {@code
 * org.springframework.web.client} surface to one class.
 *
 * <p>Per ADR-002 the calls are blocking {@code RestClient} calls; bounded concurrency over the
 * detail calls is the adapter's concern (via {@code BoundedDetailFetcher}), not this client's.
 */
class EventSourceClient {

  private static final String PRODUCT = "InsightIDR ";
  private static final String SEARCH_PATH =
      "/api/3/organizations/{orgId}/eventsources/search?query=";
  private static final String DETAIL_PATH = "/api/3/organizations/{orgId}/eventsources/{id}";

  private final RestClient searchClient;
  private final RestClient detailClient;

  EventSourceClient(RestClient searchClient, RestClient detailClient) {
    this.searchClient = searchClient;
    this.detailClient = detailClient;
  }

  /** Search (list) call — lightweight rows, no health. Empty list when the body is absent. */
  List<EventSourceSearchDto> search(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    EventSourceSearchDto[] body =
        execute(
            "search",
            () ->
                searchClient
                    .get()
                    .uri(SEARCH_PATH, orgId)
                    // Inbound X-IPIMS-* identity headers (Class 3 Layer B) are forwarded as-is.
                    // Canonical Layer A service-identity headers will be attached by track-10's
                    // Class3HeaderAttacher once it lands.
                    .headers(h -> h.addAll(authHeaders))
                    .retrieve()
                    .body(EventSourceSearchDto[].class));
    return body == null ? List.of() : List.of(body);
  }

  /** Per-source detail call — supplies the health fields the search response lacks. */
  EventSourceDetailsDto detail(String orgId, String id, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    return execute(
        "detail",
        () ->
            detailClient
                .get()
                .uri(DETAIL_PATH, orgId, id)
                // Inbound X-IPIMS-* identity headers (Class 3 Layer B) are forwarded as-is.
                // Canonical Layer A service-identity headers will be attached by track-10's
                // Class3HeaderAttacher once it lands.
                .headers(h -> h.addAll(authHeaders))
                .retrieve()
                .body(EventSourceDetailsDto.class));
  }

  /**
   * Run a {@code RestClient} call and map its failure modes to the adapter exception family,
   * preserving the cause: 401/403 → auth, 5xx → upstream, timeout/transport → timeout, any other
   * client-side failure (e.g. unparseable body) → upstream-broken. {@code phase} ("search" /
   * "detail") tags the diagnostics. Shared by both calls so the mapping lives in one place.
   */
  private static <T> T execute(String phase, Supplier<T> call)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    try {
      return call.get();
    } catch (HttpClientErrorException e) {
      throwClientError(phase, e);
      throw new IllegalStateException("unreachable: throwClientError always throws");
    } catch (HttpServerErrorException e) {
      throw new AdapterUpstreamException(PRODUCT + phase + " 5xx: " + e.getStatusCode(), e);
    } catch (ResourceAccessException e) {
      throw new AdapterTimeoutException(
          PRODUCT + phase + " failed (timeout/transport): " + e.getMessage(), e);
    } catch (RestClientException e) {
      throw new AdapterUpstreamException(
          PRODUCT + phase + " response could not be processed: " + e.getMessage(), e);
    }
  }

  /**
   * Map a 4xx to the matching adapter exception, preserving the cause: 401/403 are auth failures;
   * every other 4xx is upstream-broken (the contract exposes no distinct 4xx signal). Always throws
   * — mirrors {@code InsightConnectAdapter.throwClientError}.
   */
  private static void throwClientError(String phase, HttpClientErrorException e)
      throws AdapterAuthException, AdapterUpstreamException {
    int code = e.getStatusCode().value();
    if (code == 401 || code == 403) {
      throw new AdapterAuthException(PRODUCT + phase + " auth failure: " + e.getStatusCode(), e);
    }
    throw new AdapterUpstreamException(PRODUCT + phase + " 4xx: " + e.getStatusCode(), e);
  }
}
