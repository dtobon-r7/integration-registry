package com.rapid7.integrationregistry.adapter.insightconnect;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Spring configuration for the InsightConnect adapter. Activates
 * {@link InsightConnectProperties} and exposes a {@link RestClient} bean built
 * from a {@link RestClient.Builder} with the configured base URL and per-call
 * connect/read timeout.
 *
 * <p>The builder is configured then built here; contract tests construct their
 * own builder and bind a {@code MockRestServiceServer} to it (see
 * {@code InsightConnectAdapterContractTest}).
 *
 * <p>The request factory is {@link JdkClientHttpRequestFactory} over a pooled
 * {@link HttpClient}, so connections (and TLS sessions) are reused across the
 * per-fetch calls the T07 coordinator will make. The connect timeout lives on
 * the {@code HttpClient}; the per-request read timeout lives on the factory —
 * both sourced from {@code properties.timeout()}.
 *
 * <p>{@link ConnectionStatusMapper} is exposed as a {@code @Bean} here rather
 * than carrying a {@code @Component} annotation — it is pure logic with no
 * Spring dependency, and registering it via configuration keeps it
 * framework-free (mirrors how {@code VendorMappingConfiguration} declares
 * {@code BundleParser}). {@link InsightConnectAdapter} autowires it.
 */
@Configuration
@EnableConfigurationProperties(InsightConnectProperties.class)
public class InsightConnectClientConfig {

    // The HttpClient is deliberately long-lived: it backs a singleton RestClient bean
    // and pools connections across the per-fetch calls T07 makes. Closing it here would
    // defeat the pooling; its lifecycle is the application context's, not this method's.
    @SuppressWarnings("PMD.CloseResource")
    @Bean
    public RestClient insightConnectRestClient(InsightConnectProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    }

    @Bean
    public ConnectionStatusMapper connectionStatusMapper() {
        return new ConnectionStatusMapper();
    }
}
