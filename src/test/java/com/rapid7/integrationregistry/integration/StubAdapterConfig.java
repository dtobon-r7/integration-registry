package com.rapid7.integrationregistry.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies exactly the two stub adapters the coordinator autowires for the WP-04 read-path suite.
 * The real {@code InsightConnectAdapter} @Component is removed from the scan by {@code
 * ReadPathTestSupport.ExcludeRealAdapters}, so these two beans are the only {@code
 * IntegrationAdapter}s in the context. Both have hard-coded product names, so the coordinator's
 * constructor-time validation passes at boot with exactly {@code InsightConnect} + {@code
 * InsightIDR}. Top-level (not nested) so Boot does not ambiguously treat it as default test config
 * — it is applied solely via the explicit {@code @Import} on {@code ReadPathTestSupport}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAdapterConfig {

  @Bean
  StubAdapter insightConnectAdapter() {
    return new StubAdapter(ReadPathTestSupport.INSIGHT_CONNECT);
  }

  @Bean
  StubAdapter insightIdrAdapter() {
    return new StubAdapter(ReadPathTestSupport.INSIGHT_IDR);
  }
}
