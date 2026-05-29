package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

/**
 * InsightConnect adapter. Fetches automation connections from
 * {@code GET /api/public/v1/connections?includeTests=1} and normalizes each to
 * a {@link NormalizedIntegration} (RFC-001 §InsightConnectAdapter).
 *
 * <p>Per ADR-002 the outbound call uses {@link RestClient}. Async fan-out is
 * the T07 coordinator's concern; this adapter makes one blocking call.
 */
@Component
public class InsightConnectAdapter implements IntegrationAdapter {

    static final String PRODUCT_NAME = "InsightConnect";
    static final String INTEGRATION_TYPE = "Automation Plugin";
    static final String SOURCE_TYPE = "plugin_name";
    private static final String CONNECTIONS_PATH = "/api/public/v1/connections?includeTests=1";

    private final RestClient restClient;
    private final ConnectionStatusMapper statusMapper;
    private final InsightConnectProperties properties;

    public InsightConnectAdapter(RestClient insightConnectRestClient,
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

        List<NormalizedIntegration> integrations = connections.stream()
            .map(cvm -> normalize(cvm, orgId))
            .toList();

        return new FetchResult(integrations, Instant.now());
    }

    private ConnectionsResponse call(HttpHeaders authHeaders)
            throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
        try {
            return restClient.get()
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
     * Map a 4xx to the matching adapter exception, preserving the cause: 401/403
     * are auth failures; every other 4xx is treated as upstream-broken (the
     * contract exposes no distinct 4xx signal). Always throws.
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
        ConnectionTest mostRecent = statusMapper.mostRecentByCreatedAt(cvm.connectionTests());
        String orchestratorStatus = cvm.orchestrator() == null ? null : cvm.orchestrator().status();
        IntegrationStatus status = statusMapper.deriveStatus(orchestratorStatus, mostRecent);
        Instant lastSuccess = statusMapper.deriveLastSuccess(cvm.connectionTests());
        String pluginName = cvm.plugin() == null ? null : cvm.plugin().name();

        return new NormalizedIntegration(
            cvm.id(),
            new SourceIdentifier(SOURCE_TYPE, pluginName),
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            null,                       // integration_label: ICON has no per-instance name
            status,
            lastSuccess,
            configurationUrl(cvm),
            orgId);
    }

    private String configurationUrl(ConnectionViewModel cvm) {
        String apiUrl = cvm.configurationUrl();
        if (apiUrl != null && !apiUrl.isBlank()) {
            return apiUrl;
        }
        return properties.iconBase() + "/automation/connections/" + cvm.id();
    }
}
