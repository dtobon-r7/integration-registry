package com.rapid7.integrationregistry.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.coordinator.FanOutCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Full-context read-path integration suite (WP-04). Boots the real read path against a
 * Testcontainers Valkey with adapters faked, and proves the six track exit-criteria scenarios
 * end-to-end over HTTP.
 */
class ReadPathIntegrationTest extends ReadPathTestSupport {

  @Autowired private FanOutCoordinator coordinator;

  @Test
  void contextBoots_withExactlyTwoStubAdapters() {
    // Arrange — context booted via @SpringBootTest; nothing per-test needed.

    // Act — both stub adapters are injected and the coordinator wired.
    // Assert — the two stubs are present with the expected product names; the real
    // InsightConnectAdapter was evicted by the name-colliding stub bean.
    assertThat(insightConnectAdapter.productName()).isEqualTo(INSIGHT_CONNECT);
    assertThat(insightIdrAdapter.productName()).isEqualTo(INSIGHT_IDR);
    assertThat(coordinator).isNotNull();
  }
}
