package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductOutcomeTest {

  private static final Instant FETCHED_AT = Instant.parse("2026-06-01T12:00:00Z");

  @Test
  void served_shouldExposeFields_whenFreshHit() {
    // Arrange / Act
    ProductOutcome.Served served =
        new ProductOutcome.Served(
            "InsightConnect", List.of(), FETCHED_AT, true, false, Optional.empty());

    // Assert
    assertThat(served.productName()).isEqualTo("InsightConnect");
    assertThat(served.cacheHitPerProduct()).isTrue();
    assertThat(served.stale()).isFalse();
    assertThat(served.staleSince()).isEmpty();
  }

  @Test
  void served_shouldRejectStaleWithoutStaleSince() {
    // Act / Assert: stale=true but staleSince empty is an illegal state
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightIDR", List.of(), FETCHED_AT, false, true, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void served_shouldRejectStaleSinceWhenNotStale() {
    // Act / Assert: staleSince present but stale=false is an illegal state
    assertThatThrownBy(
            () ->
                new ProductOutcome.Served(
                    "InsightIDR", List.of(), FETCHED_AT, false, false, Optional.of(FETCHED_AT)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void served_shouldDefensivelyCopyIntegrations() {
    // Arrange
    var mutable = new java.util.ArrayList<NormalizedIntegration>();

    // Act
    ProductOutcome.Served served =
        new ProductOutcome.Served(
            "InsightConnect", mutable, FETCHED_AT, true, false, Optional.empty());
    mutable.add(null); // mutate the source list after construction

    // Assert: the record's copy is unaffected
    assertThat(served.integrations()).isEmpty();
  }

  @Test
  void unavailable_shouldExposeReason_whenOmitted() {
    // Arrange / Act
    ProductOutcome.Unavailable unavailable =
        new ProductOutcome.Unavailable("InsightIDR", "timeout");

    // Assert
    assertThat(unavailable.productName()).isEqualTo("InsightIDR");
    assertThat(unavailable.reason()).isEqualTo("timeout");
  }

  @Test
  void unavailable_shouldRejectBlankReason() {
    // Act / Assert
    assertThatThrownBy(() -> new ProductOutcome.Unavailable("InsightIDR", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
