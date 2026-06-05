package com.rapid7.integrationregistry.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Service-layer beans. Supplies the {@link Clock} {@link VendorService} uses for the as_of
 * freshness floor.
 */
@Configuration
public class ServiceConfiguration {

  /**
   * The clock {@link VendorService} reads when no product contributed a {@code fetched_at} (the
   * all-adapter-failure case), so {@code metadata.as_of} is "as of now, nothing fresh is known". A
   * bean (not {@code Instant.now()}) so tests can inject a fixed clock.
   */
  @Bean
  public Clock registryClock() {
    return Clock.systemUTC();
  }
}
