package com.rapid7.integrationregistry.adapter.insightconnect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import com.rapid7.integrationregistry.testsupport.FixtureLoader;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class InsightConnectAdapterContractTest {

  private static final String BASE_URL = "https://icon.test.local";
  private static final String ICON_BASE = "https://icon.test.local";
  private static final String CONNECTIONS_URL =
      BASE_URL + "/api/class3/v1/connections?includeTests=1";
  private static final String ORG_ID = "org-123";

  /**
   * Builds an adapter whose RestClient is bound to a MockRestServiceServer. bindTo mutates the
   * builder's request factory in place, so the client is built AFTER bindTo (mirrors
   * SampleContractTest).
   */
  private record Harness(InsightConnectAdapter adapter, MockRestServiceServer server) {}

  private static Harness harness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    InsightConnectProperties props =
        new InsightConnectProperties(BASE_URL, ICON_BASE, java.time.Duration.ofSeconds(5));
    InsightConnectAdapter adapter =
        new InsightConnectAdapter(client, new ConnectionStatusMapper(), props);
    return new Harness(adapter, server);
  }

  private static void stub(MockRestServiceServer server, String fixture) {
    server
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(
            withSuccess(
                FixtureLoader.read("insightconnect/" + fixture), MediaType.APPLICATION_JSON));
  }

  /**
   * Attaches a logback {@link ListAppender} to the adapter's logger to capture emitted events.
   * {@link LogCapture#close()} detaches it again — use in a try-with-resources so the appender
   * never leaks onto the shared logger.
   */
  private static LogCapture captureAdapterLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(InsightConnectAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, appender);
  }

  private record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender)
      implements AutoCloseable {
    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void productName_shouldReturnInsightConnect() {
    assertThat(harness().adapter().productName()).isEqualTo("InsightConnect");
  }

  @Test
  void fetch_shouldReturnHealthyIntegration_whenOrchestratorHealthyAndTestSuccess()
      throws Exception {
    // Arrange
    Harness h = harness();
    stub(h.server(), "healthy.json");
    // Act
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    // Assert
    assertThat(result.integrations()).hasSize(1);
    NormalizedIntegration n = result.integrations().get(0);
    assertThat(n.status()).isEqualTo(IntegrationStatus.HEALTHY);
    assertThat(n.sourceIdentifier().sourceType()).isEqualTo("plugin_name");
    assertThat(n.sourceIdentifier().sourceValue()).isEqualTo("jira");
    assertThat(n.productName()).isEqualTo("InsightConnect");
    assertThat(n.integrationType()).isEqualTo("Automation Plugin");
    assertThat(n.integrationLabel()).isNull();
    assertThat(n.customerAccountId()).isEqualTo(ORG_ID);
    assertThat(n.configurationUrl())
        .isEqualTo(ICON_BASE + "/automation/connections/c1a2b3c4-0001-0001-0001-000000000001");
    assertThat(n.lastSuccessTimestamp()).isEqualTo(Instant.parse("2026-05-19T10:00:00Z"));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnError_whenTestFailed() throws Exception {
    Harness h = harness();
    stub(h.server(), "error.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.ERROR);
    assertThat(result.integrations().get(0).lastSuccessTimestamp()).isNull();
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnMissingData_whenTestStale() throws Exception {
    Harness h = harness();
    stub(h.server(), "missing-data-stale.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.MISSING_DATA);
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnMissingData_whenOrchestratorUnknown() throws Exception {
    Harness h = harness();
    stub(h.server(), "missing-data-unknown.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.MISSING_DATA);
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnWarning_whenOrchestratorWarning() throws Exception {
    Harness h = harness();
    stub(h.server(), "warning.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.WARNING);
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnDisabled_whenOrchestratorStopped() throws Exception {
    Harness h = harness();
    stub(h.server(), "disabled.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.DISABLED);
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnError_whenTestFailedAndOrchestratorWarning() throws Exception {
    Harness h = harness();
    stub(h.server(), "precedence-failed-and-warning.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.ERROR);
    h.server().verify();
  }

  @Test
  void fetch_shouldNormalizeAllConnections_whenMultipleReturned() throws Exception {
    // Arrange
    Harness h = harness();
    stub(h.server(), "multi-connection.json");
    // Act
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    // Assert — order-independent
    assertThat(result.integrations()).hasSize(3);
    assertThat(result.integrations())
        .extracting(n -> n.sourceIdentifier().sourceValue())
        .containsExactlyInAnyOrder("jira", "jira", "microsoft-defender");
    // The jira/warning/stale/failed connection resolves to ERROR (precedence)
    assertThat(result.integrations())
        .filteredOn(n -> n.integrationId().equals("c1a2b3c4-0002-0002-0002-000000000002"))
        .singleElement()
        .satisfies(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.ERROR));
    h.server().verify();
  }

  @Test
  void fetch_shouldSkipMalformedRecords_whenIdOrPluginNameMissing() throws Exception {
    // Arrange — fixture has 1 valid + 2 malformed (null plugin.slugName, missing id)
    Harness h = harness();
    stub(h.server(), "malformed-skipped.json");
    // Act
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    // Assert — only the valid connection survives; one bad record must not fail the batch
    assertThat(result.integrations()).hasSize(1);
    assertThat(result.integrations().get(0).integrationId())
        .isEqualTo("c1a2b3c4-0001-0001-0001-000000000001");
    h.server().verify();
  }

  @Test
  void fetch_shouldWarn_whenSkippingMalformedRecord() throws Exception {
    // Arrange — capture logs; fixture has 2 malformed records (missing id, missing plugin slug)
    Harness h = harness();
    stub(h.server(), "malformed-skipped.json");
    try (LogCapture logs = captureAdapterLogs()) {
      // Act
      h.adapter().fetch(ORG_ID, new HttpHeaders());
      // Assert — each skip is observable to a curator via a WARN
      assertThat(logs.appender().list)
          .filteredOn(e -> e.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("missing id"))
          .anySatisfy(m -> assertThat(m).contains("missing plugin slug"));
    }
    h.server().verify();
  }

  @Test
  void fetch_shouldStillReturnConnections_whenMetadataTotalExceedsReturned() throws Exception {
    // Arrange — data.meta.total (5) > connections.size() (1): a pagination-truncation signal.
    // The adapter logs a WARN but still returns what it got (no throw, no drop).
    Harness h = harness();
    String body =
        """
            { "data": { "connections": [ {
                "id": "c-1",
                "name": "Jira",
                "plugin": { "name": "Jira", "slugName": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
                "orchestrator": { "id": "o", "name": "Orch", "status": "healthy", "version": "3" },
                "tests": [
                    { "id": "ct", "connectionId": "c-1", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T10:00:00Z" }
                ]
            } ], "meta": { "total": 5 } } }
            """;
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    // Act
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    // Assert — the returned page is still surfaced
    assertThat(result.integrations()).hasSize(1);
    h.server().verify();
  }

  @Test
  void fetch_shouldPreferApiReturnedConfigurationUrl_whenPresent() throws Exception {
    // Arrange — inline body carrying an explicit configurationUrl
    Harness h = harness();
    String body =
        """
            { "data": { "connections": [ {
                "id": "c-9",
                "name": "Jira With URL",
                "plugin": { "name": "Jira", "slugName": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
                "orchestrator": { "id": "o", "name": "Orch", "status": "healthy", "version": "3" },
                "configurationUrl": "https://custom.example/connections/c-9",
                "tests": [
                    { "id": "ct", "connectionId": "c-9", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T10:00:00Z" }
                ]
            } ], "meta": { "total": 1 } } }
            """;
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    // Act
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    // Assert
    assertThat(result.integrations().get(0).configurationUrl())
        .isEqualTo("https://custom.example/connections/c-9");
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowUpstream_whenServerReturns503() {
    Harness h = harness();
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withServerError());
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterUpstreamException.class)
        .satisfies(
            ex -> {
              assertThat(((AdapterUpstreamException) ex).reasonCode()).isEqualTo("upstream_5xx");
              assertThat(ex.getCause()).isNotNull();
            });
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowAuth_whenServerReturns401() {
    Harness h = harness();
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withUnauthorizedRequest());
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterAuthException.class)
        .satisfies(
            ex -> assertThat(((AdapterAuthException) ex).reasonCode()).isEqualTo("auth_failure"));
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowAuth_whenServerReturns403() {
    Harness h = harness();
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterAuthException.class);
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowUpstream_whenServerReturns404() {
    Harness h = harness();
    h.server()
        .expect(requestTo(CONNECTIONS_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterUpstreamException.class);
    h.server().verify();
  }
}
