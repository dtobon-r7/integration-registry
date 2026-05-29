package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
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
 * InsightConnect adapter. Fetches automation connections from {@code GET
 * /api/public/v1/connections?includeTests=1} and normalizes each to a {@link NormalizedIntegration}
 * (RFC-001 §InsightConnectAdapter).
 *
 * <p>Per ADR-002 the outbound call uses {@link RestClient}. Async fan-out is the T07 coordinator's
 * concern; this adapter makes one blocking call.
 */
@Component
public class InsightConnectAdapter implements IntegrationAdapter {

  private static final Logger log = LoggerFactory.getLogger(InsightConnectAdapter.class);

  static final String PRODUCT_NAME = "InsightConnect";
  static final String INTEGRATION_TYPE = "Automation Plugin";
  static final String SOURCE_TYPE = "plugin_name";
  private static final String CONNECTIONS_PATH = "/api/public/v1/connections?includeTests=1";

  private final RestClient restClient;
  private final ConnectionStatusMapper statusMapper;
  private final InsightConnectProperties properties;

  public InsightConnectAdapter(
      RestClient insightConnectRestClient,
      ConnectionStatusMapper statusMapper,
      InsightConnectProperties properties) {
    this.restClient = insightConnectRestClient;
    this.statusMapper = statusMapper;
    this.properties = properties;
  }

  @Override
  public String productName() {
    return PRODUCT_NAME;
  }

  @Override
  public FetchResult fetch(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {

    ConnectionsResponse response = call(authHeaders);
    List<ConnectionViewModel> connections =
        response == null || response.data() == null ? List.of() : response.data();

    warnIfTruncated(response, connections.size());

    // Skip records missing the identity fields we cannot normalize (id, plugin name)
    // rather than throwing — one malformed connection must not zero out the whole
    // InsightConnect view. Skips are logged so a curator can investigate.
    List<NormalizedIntegration> integrations =
        connections.stream()
            .filter(this::isNormalizable)
            .map(cvm -> normalize(cvm, orgId))
            .toList();

    return new FetchResult(integrations, Instant.now());
  }

  /**
   * Warn when the response carries a {@code metadata.total} greater than the number of connections
   * actually returned — a signal that ICON paginated and this single-page fetch under-reports.
   * RFC-001 states a single call returns everything for ICON; this tripwire makes a violation of
   * that assumption visible instead of silently truncating. (Paged fetching is a future follow-up.)
   */
  private void warnIfTruncated(ConnectionsResponse response, int returned) {
    if (response == null || response.metadata() == null) {
      return;
    }
    Integer total = response.metadata().total();
    if (total != null && total > returned) {
      log.warn(
          "InsightConnect reported total={} but returned {} connections; "
              + "response may be paginated and under-reported",
          total,
          returned);
    }
  }

  /**
   * A connection is normalizable only if it carries the identity fields the normalized record
   * requires: a non-blank {@code id} (the integration id) and a non-blank {@code plugin.name} (the
   * source identifier). Records missing either are skipped with a WARN.
   */
  private boolean isNormalizable(ConnectionViewModel cvm) {
    if (!StringUtils.hasText(cvm.id())) {
      log.warn("Skipping InsightConnect connection with missing id");
      return false;
    }
    String pluginName = cvm.plugin() == null ? null : cvm.plugin().name();
    if (!StringUtils.hasText(pluginName)) {
      log.warn("Skipping InsightConnect connection '{}' with missing plugin name", cvm.id());
      return false;
    }
    return true;
  }

  private ConnectionsResponse call(HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    try {
      return restClient
          .get()
          .uri(CONNECTIONS_PATH)
          // T10 integration point: hand-rolled identity-header forwarding for now;
          // swap for the canonical Class3HeaderAttacher once track 10 lands.
          .headers(h -> h.addAll(authHeaders))
          .retrieve()
          .body(ConnectionsResponse.class);
    } catch (HttpClientErrorException e) {
      throwClientError(e);
      return null; // unreachable: throwClientError always throws
    } catch (HttpServerErrorException e) {
      throw new AdapterUpstreamException("InsightConnect 5xx: " + e.getStatusCode(), e);
    } catch (ResourceAccessException e) {
      throw new AdapterTimeoutException(
          "InsightConnect request failed (timeout/transport): " + e.getMessage(), e);
    } catch (RestClientException e) {
      // Any other client-side failure (e.g. unparseable body) — upstream-broken.
      throw new AdapterUpstreamException(
          "InsightConnect response could not be processed: " + e.getMessage(), e);
    }
  }

  /**
   * Map a 4xx to the matching adapter exception, preserving the cause: 401/403 are auth failures;
   * every other 4xx is treated as upstream-broken (the contract exposes no distinct 4xx signal).
   * Always throws — extracted so {@link #call} stays within the complexity budget while keeping
   * each thrown type concrete (so {@code fetch}'s declared signature stays precise).
   */
  private static void throwClientError(HttpClientErrorException e)
      throws AdapterAuthException, AdapterUpstreamException {
    int code = e.getStatusCode().value();
    if (code == 401 || code == 403) {
      throw new AdapterAuthException("InsightConnect auth failure: " + e.getStatusCode(), e);
    }
    throw new AdapterUpstreamException("InsightConnect 4xx: " + e.getStatusCode(), e);
  }

  private NormalizedIntegration normalize(ConnectionViewModel cvm, String orgId) {
    // id and plugin.name are guaranteed non-blank here by isNormalizable().
    String orchestratorStatus = cvm.orchestrator() == null ? null : cvm.orchestrator().status();
    IntegrationStatus status = statusMapper.deriveStatus(orchestratorStatus, cvm.connectionTests());
    Instant lastSuccess = statusMapper.deriveLastSuccess(cvm.connectionTests());
    String pluginName = cvm.plugin().name();

    return new NormalizedIntegration(
        cvm.id(),
        new SourceIdentifier(SOURCE_TYPE, pluginName),
        PRODUCT_NAME,
        INTEGRATION_TYPE,
        null, // integration_label: ICON has no per-instance name
        status,
        lastSuccess,
        configurationUrl(cvm),
        orgId);
  }

  private String configurationUrl(ConnectionViewModel cvm) {
    String apiUrl = cvm.configurationUrl();
    if (StringUtils.hasText(apiUrl)) {
      return apiUrl;
    }
    return properties.iconBase() + "/automation/connections/" + cvm.id();
  }
}
