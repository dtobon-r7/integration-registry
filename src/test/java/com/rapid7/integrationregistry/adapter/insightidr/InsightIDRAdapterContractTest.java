package com.rapid7.integrationregistry.adapter.insightidr;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class InsightIDRAdapterContractTest {

  private static final String BASE_URL = "https://idr.test.local";
  private static final String IDR_BASE = "https://idr.test.local";
  private static final String ORG_ID = "1f78ace5-bdef-4512-abed-4c96548dc043";
  private static final String SEARCH_URL =
      BASE_URL + "/api/3/organizations/" + ORG_ID + "/eventsources/search?query=";

  private static String detailUrl(String id) {
    return BASE_URL + "/api/3/organizations/" + ORG_ID + "/eventsources/" + id;
  }

  // Pinned clock so staleness is deterministic against the fixtures' lastActive values
  // (FRESH=1782367200000 6h before; STALE=1782194400000 54h before). The adapter reads this
  // Clock instead of wall-clock Instant.now(), so the healthy/stale fixtures never rot.
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

  private record Harness(InsightIDRAdapter adapter, MockRestServiceServer server) {}

  private static Harness harness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    // ignoreExpectOrder(true): the bounded-concurrency detail fan-out issues detail calls in a
    // nondeterministic order, so the mock must match expectations regardless of arrival order.
    MockRestServiceServer server =
        MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    RestClient client = builder.build();
    InsightIDRProperties props =
        new InsightIDRProperties(
            BASE_URL,
            IDR_BASE,
            Duration.ofSeconds(15),
            Duration.ofSeconds(2),
            Duration.ofHours(24),
            5);
    InsightIDRAdapter adapter =
        new InsightIDRAdapter(
            new EventSourceClient(client),
            new EventSourceStatusMapper(),
            new BoundedDetailFetcher(),
            props,
            FIXED_CLOCK);
    return new Harness(adapter, server);
  }

  private static void stubSearch(MockRestServiceServer server, String fixture) {
    server
        .expect(requestTo(SEARCH_URL))
        .andExpect(method(GET))
        .andRespond(
            withSuccess(FixtureLoader.read("insightidr/" + fixture), MediaType.APPLICATION_JSON));
  }

  private static void stubDetail(MockRestServiceServer server, String id, String fixture) {
    server
        .expect(requestTo(detailUrl(id)))
        .andExpect(method(GET))
        .andRespond(
            withSuccess(FixtureLoader.read("insightidr/" + fixture), MediaType.APPLICATION_JSON));
  }

  @Test
  void productName_shouldReturnInsightIDR() {
    assertThat(harness().adapter().productName()).isEqualTo("InsightIDR");
  }

  @Test
  void fetch_shouldReturnHealthy_whenStatusActiveAndFresh() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-healthy.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-healthy.json");

    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());

    assertThat(result.integrations()).hasSize(2);
    NormalizedIntegration n =
        result.integrations().stream()
            .filter(i -> i.integrationId().equals("es-0001-0001-0001"))
            .findFirst()
            .orElseThrow();
    assertThat(n.status()).isEqualTo(IntegrationStatus.HEALTHY);
    assertThat(n.sourceIdentifier().sourceType()).isEqualTo("product_type");
    assertThat(n.sourceIdentifier().sourceValue()).isEqualTo("MICROSOFT_DEFENDER");
    assertThat(n.productName()).isEqualTo("InsightIDR");
    assertThat(n.integrationType()).isEqualTo("SIEM Event Source");
    assertThat(n.integrationLabel()).isEqualTo("Microsoft Defender for Endpoint — Primary");
    assertThat(n.customerAccountId()).isEqualTo(ORG_ID);
    assertThat(n.configurationUrl()).isEqualTo(IDR_BASE + "/eventsources/es-0001-0001-0001");
    // last_success_timestamp is the ISO conversion of the fixture's lastActive (FRESH epoch ms)
    assertThat(n.lastSuccessTimestamp()).isEqualTo(Instant.parse("2026-06-25T06:00:00Z"));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnWarning_whenIssuePresentNonFatal() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-warning.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-warning.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations())
        .allSatisfy(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.WARNING));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnError_whenStatusError() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-error.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-error.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations())
        .allSatisfy(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.ERROR));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnDisabled_whenStatusPaused() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-disabled.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-disabled.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations())
        .allSatisfy(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.DISABLED));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnMissingData_whenLastActiveNull() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-null-lastactive.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-null-lastactive.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations())
        .allSatisfy(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.MISSING_DATA));
    h.server().verify();
  }

  @Test
  void fetch_shouldReturnMissingData_whenLastActiveStale() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-stale.json");
    stubDetail(h.server(), "es-0002-0002-0002", "detail-stale.json");
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations())
        .allSatisfy(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.MISSING_DATA));
    h.server().verify();
  }

  @Test
  void fetch_shouldEmitProductNameFallbackAndWarn_whenProductTypeAbsent() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-producttype-absent.json");
    stubDetail(h.server(), "es-0003-0003-0003", "detail-healthy.json");

    Logger logger = (Logger) LoggerFactory.getLogger(InsightIDRAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
      NormalizedIntegration n = result.integrations().get(0);
      assertThat(n.sourceIdentifier().sourceType()).isEqualTo("product_name");
      assertThat(n.sourceIdentifier().sourceValue()).isEqualTo("Microsoft Defender for Endpoint");
      assertThat(appender.list)
          .filteredOn(e -> e.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("es-0003-0003-0003"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowAuth_whenSearchReturns401() {
    Harness h = harness();
    h.server()
        .expect(requestTo(SEARCH_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withUnauthorizedRequest());
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterAuthException.class);
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowUpstream_whenSearchReturns503() {
    Harness h = harness();
    h.server()
        .expect(requestTo(SEARCH_URL))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withServerError());
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterUpstreamException.class);
    h.server().verify();
  }

  @Test
  void fetch_shouldMapSourceToMissingData_whenItsDetailCallFails() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    stubDetail(h.server(), "es-0001-0001-0001", "detail-healthy.json");
    // second source's detail call 503s — that source becomes missing_data, the fetch still succeeds
    h.server()
        .expect(requestTo(detailUrl("es-0002-0002-0002")))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withServerError());

    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());

    assertThat(result.integrations()).hasSize(2);
    assertThat(result.integrations())
        .filteredOn(n -> n.integrationId().equals("es-0002-0002-0002"))
        .singleElement()
        .satisfies(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.MISSING_DATA));
    assertThat(result.integrations())
        .filteredOn(n -> n.integrationId().equals("es-0001-0001-0001"))
        .singleElement()
        .satisfies(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.HEALTHY));
    h.server().verify();
  }

  @Test
  void fetch_shouldThrowAuth_whenADetailCallReturns403() {
    Harness h = harness();
    stubSearch(h.server(), "search-list.json");
    // Use a single-source-style expectation: the detail 403 must abort the whole fetch.
    h.server()
        .expect(requestTo(detailUrl("es-0001-0001-0001")))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));
    h.server()
        .expect(requestTo(detailUrl("es-0002-0002-0002")))
        .andExpect(method(GET))
        .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
        .isInstanceOf(AdapterAuthException.class);
  }

  @Test
  void fetch_shouldBoundDetailConcurrency_whenManySources() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-multi.json");
    // MockRestServiceServer is single-threaded for matching; stub every detail id es-100..es-149.
    for (int i = 100; i < 150; i++) {
      stubDetail(h.server(), "es-" + i, "detail-healthy.json");
    }
    FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
    assertThat(result.integrations()).hasSize(50);
    h.server().verify();
  }

  @Test
  void fetch_shouldSkipSourceWithNoIdentifiers_andWarn() throws Exception {
    Harness h = harness();
    stubSearch(h.server(), "search-no-identifiers.json");
    // Only stub the detail call for the valid source; the skipped one must never be fetched
    stubDetail(h.server(), "es-valid-0001", "detail-healthy.json");

    Logger logger = (Logger) LoggerFactory.getLogger(InsightIDRAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());

      // Only the valid source should be present
      assertThat(result.integrations()).hasSize(1);
      assertThat(result.integrations().get(0).integrationId()).isEqualTo("es-valid-0001");

      // The skipped source should not appear
      assertThat(result.integrations()).noneMatch(n -> n.integrationId().equals("es-noid-0002"));

      // A WARN must have been logged mentioning the skipped source's id
      assertThat(appender.list)
          .filteredOn(e -> e.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("es-noid-0002"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    h.server().verify();
  }
}
