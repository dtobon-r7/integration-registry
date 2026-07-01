package com.rapid7.integrationregistry.adapter.insightidr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InsightIDRPropertiesTest {

  @Test
  void constructor_shouldApplyDefaults_whenOptionalFieldsNull() {
    InsightIDRProperties p =
        new InsightIDRProperties("https://idr.test", "https://idr.test", null, null, null, null);
    assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(p.detailTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.stalenessThreshold()).isEqualTo(Duration.ofHours(24));
    assertThat(p.detailConcurrency()).isEqualTo(60);
  }

  @Test
  void constructor_shouldStripTrailingSlash_fromUrls() {
    InsightIDRProperties p =
        new InsightIDRProperties("https://idr.test/", "https://idr.test/", null, null, null, null);
    assertThat(p.baseUrl()).isEqualTo("https://idr.test");
    assertThat(p.idrBase()).isEqualTo("https://idr.test");
  }

  @Test
  void constructor_shouldReject_whenBaseUrlBlank() {
    assertThatThrownBy(
            () -> new InsightIDRProperties("  ", "https://idr.test", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_shouldReject_whenIdrBaseNull() {
    assertThatThrownBy(
            () -> new InsightIDRProperties("https://idr.test", null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructor_shouldReject_whenDetailConcurrencyNotPositive() {
    assertThatThrownBy(
            () ->
                new InsightIDRProperties(
                    "https://idr.test", "https://idr.test", null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
