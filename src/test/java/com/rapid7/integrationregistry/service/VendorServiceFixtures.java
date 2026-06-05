package com.rapid7.integrationregistry.service;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.coordinator.ProductOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Hand-built fixtures for VendorService tests — no Spring, no Mockito needed to build these. */
final class VendorServiceFixtures {

  private VendorServiceFixtures() {}

  static ProductOutcome served(String product, Instant fetchedAt, boolean cacheHit) {
    return new ProductOutcome.Served(
        product,
        List.of(anIntegration(product)),
        fetchedAt,
        cacheHit,
        false,
        Optional.empty(),
        Optional.empty());
  }

  static ProductOutcome staleServed(
      String product, Instant fetchedAt, Instant staleSince, String reason) {
    return new ProductOutcome.Served(
        product,
        List.of(anIntegration(product)),
        fetchedAt,
        false,
        true,
        Optional.of(staleSince),
        Optional.of(reason));
  }

  static ProductOutcome unavailable(String product, String reason) {
    return new ProductOutcome.Unavailable(product, reason);
  }

  static NormalizedIntegration anIntegration(String product) {
    return new NormalizedIntegration(
        "i-1",
        new SourceIdentifier("product_type", "microsoft-defender-endpoint"),
        product,
        "SIEM Event Source",
        "label-i-1",
        IntegrationStatus.HEALTHY,
        Instant.parse("2026-06-01T00:00:00Z"),
        "https://example/config",
        "test-org");
  }
}
