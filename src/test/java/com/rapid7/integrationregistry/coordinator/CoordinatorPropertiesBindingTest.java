package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoordinatorPropertiesBindingTest {

  @Test
  void constructor_shouldApplyDefaults_whenNullsGiven() {
    // Act
    CoordinatorProperties props = new CoordinatorProperties(null, null, null);

    // Assert: RFC starting points / safe defaults
    assertThat(props.totalDeadline()).isEqualTo(Duration.ofSeconds(20));
    assertThat(props.defaultPerAdapterTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(props.perAdapterTimeout()).isEmpty();
  }

  @Test
  void perAdapterTimeout_shouldReturnEntry_whenProductPresent() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(
            Duration.ofSeconds(20),
            Duration.ofSeconds(10),
            Map.of("InsightConnect", Duration.ofSeconds(5), "InsightIDR", Duration.ofSeconds(15)));

    // Act / Assert
    assertThat(props.perAdapterTimeoutFor("InsightConnect")).isEqualTo(Duration.ofSeconds(5));
    assertThat(props.perAdapterTimeoutFor("InsightIDR")).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void perAdapterTimeout_shouldFallBackToDefault_whenProductAbsent() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(Duration.ofSeconds(20), Duration.ofSeconds(10), Map.of());

    // Act / Assert
    assertThat(props.perAdapterTimeoutFor("Surface Command")).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void constructor_shouldRejectNonPositiveTotalDeadline() {
    // Act / Assert
    assertThatThrownBy(() -> new CoordinatorProperties(Duration.ZERO, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_shouldRejectNonPositivePerAdapterEntry() {
    // Act / Assert
    assertThatThrownBy(
            () ->
                new CoordinatorProperties(null, null, Map.of("InsightIDR", Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void perAdapterTimeout_shouldBeUnmodifiable() {
    // Arrange
    CoordinatorProperties props =
        new CoordinatorProperties(null, null, Map.of("InsightIDR", Duration.ofSeconds(15)));

    // Act / Assert
    assertThatThrownBy(() -> props.perAdapterTimeout().put("x", Duration.ofSeconds(1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
