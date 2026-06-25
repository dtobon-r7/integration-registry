package com.rapid7.integrationregistry.adapter.insightidr;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for the InsightIDR adapter. Activates {@link InsightIDRProperties} and
 * exposes a {@link RestClient} bean built with the configured base URL and connect/read timeout,
 * plus the pure {@link EventSourceStatusMapper} as a framework-free {@code @Bean} (mirrors {@code
 * InsightConnectClientConfig}).
 *
 * <p>The search call's timeout lives on this client. Per-detail-call timeouts are applied by the
 * adapter on each detail request via {@code RestClient}'s per-request settings; the bounded
 * concurrency is handled by {@link BoundedDetailFetcher}.
 */
@Configuration
@EnableConfigurationProperties(InsightIDRProperties.class)
public class InsightIDRClientConfig {

  // Long-lived HttpClient backing the singleton RestClient bean — pools connections across the
  // per-fetch search + detail calls. Closing it would defeat pooling; its lifecycle is the
  // context's.
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

  @Bean
  public EventSourceStatusMapper eventSourceStatusMapper() {
    return new EventSourceStatusMapper();
  }
}
