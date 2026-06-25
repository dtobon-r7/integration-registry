package com.rapid7.integrationregistry.adapter.insightidr;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for the InsightIDR adapter. Activates {@link InsightIDRProperties} and
 * exposes two {@link RestClient} beans built with the configured base URL and connect/read
 * timeouts, plus the pure {@link EventSourceStatusMapper} as a framework-free {@code @Bean}
 * (mirrors {@code InsightConnectClientConfig}).
 *
 * <p>The search call uses {@code insightIDRRestClient} with the per-adapter timeout (15s default).
 * Detail calls use {@code insightIDRDetailRestClient} with the per-detail-call timeout (2s
 * default). Both clients share the pooled {@link HttpClient} for connection reuse; the timeout
 * differentiation lives on the per-client {@link JdkClientHttpRequestFactory}. Bounded concurrency
 * is handled by {@link BoundedDetailFetcher}.
 */
@Configuration
@EnableConfigurationProperties(InsightIDRProperties.class)
public class InsightIDRClientConfig {

  // Long-lived HttpClient backing the two RestClient beans — pools connections across the
  // per-fetch search + detail calls. Closing it would defeat pooling; its lifecycle is the
  // context's. Both factories share this pooled client but apply distinct read timeouts.
  @SuppressWarnings("PMD.CloseResource")
  @Bean
  public RestClient insightIDRRestClient(InsightIDRProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.timeout());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * Detail-call {@link RestClient} with the per-detail-call read timeout (2s default). Shares the
   * pooled {@link HttpClient} connection pool with {@code insightIDRRestClient} but applies the
   * shorter timeout configured for per-source detail fetches.
   */
  @SuppressWarnings("PMD.CloseResource")
  @Bean
  public RestClient insightIDRDetailRestClient(InsightIDRProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.detailTimeout());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * The HTTP boundary for the event-sources API, wrapping the two {@link RestClient} beans above.
   * Owns the search + detail calls and their exception mapping, keeping {@code InsightIDRAdapter}
   * focused on orchestration and normalization.
   */
  @Bean
  public EventSourceClient eventSourceClient(
      RestClient insightIDRRestClient, RestClient insightIDRDetailRestClient) {
    return new EventSourceClient(insightIDRRestClient, insightIDRDetailRestClient);
  }

  @Bean
  public EventSourceStatusMapper eventSourceStatusMapper() {
    return new EventSourceStatusMapper();
  }

  /**
   * The bounded-concurrency detail-fetch helper. Stateless and thread-safe, so a single shared
   * instance is correct; exposed here (rather than as a {@code @Component}) to keep it
   * framework-free and unit-testable in isolation, mirroring how {@link EventSourceStatusMapper} is
   * declared.
   */
  @Bean
  public BoundedDetailFetcher boundedDetailFetcher() {
    return new BoundedDetailFetcher();
  }
}
