package com.rapid7.integrationregistry.adapter.insightconnect;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for the InsightConnect adapter. Activates
 * {@link InsightConnectProperties} and exposes a {@link RestClient} bean built
 * from a {@link RestClient.Builder} with the configured base URL and per-call
 * connect/read timeout.
 *
 * <p>The builder is configured then built here; contract tests construct their
 * own builder and bind a {@code MockRestServiceServer} to it (see
 * {@code InsightConnectAdapterContractTest}).
 */
@Configuration
@EnableConfigurationProperties(InsightConnectProperties.class)
public class InsightConnectClientConfig {

    @Bean
    public RestClient insightConnectRestClient(InsightConnectProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    }
}
