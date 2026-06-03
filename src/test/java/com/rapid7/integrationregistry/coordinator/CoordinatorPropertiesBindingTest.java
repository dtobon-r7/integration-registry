package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CoordinatorPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  // --- Real Spring binding: exercises @EnableConfigurationProperties, the prefix, record
  // constructor binding, and partial-YAML -> null-param defaulting end to end (mirrors
  // CachePropertiesBindingTest). The constructor unit tests below cover the compact-constructor
  // logic in isolation; these cover the wiring those tests cannot reach. ---

  @Test
  void binding_shouldApplyConfiguredValues_whenPropertiesPresent() {
    runner
        .withPropertyValues(
            "integration-registry.coordinator.total-deadline=30s",
            "integration-registry.coordinator.default-per-adapter-timeout=8s",
            "integration-registry.coordinator.per-adapter-timeout.InsightConnect=5s",
            "integration-registry.coordinator.per-adapter-timeout.InsightIDR=15s")
        .run(
            context -> {
              CoordinatorProperties props = context.getBean(CoordinatorProperties.class);
              assertThat(props.totalDeadline()).isEqualTo(Duration.ofSeconds(30));
              assertThat(props.defaultPerAdapterTimeout()).isEqualTo(Duration.ofSeconds(8));
              assertThat(props.perAdapterTimeoutFor("InsightConnect"))
                  .isEqualTo(Duration.ofSeconds(5));
              assertThat(props.perAdapterTimeoutFor("InsightIDR"))
                  .isEqualTo(Duration.ofSeconds(15));
            });
  }

  @Test
  void binding_shouldApplyRfcDefaults_whenPropertiesAbsent() {
    runner.run(
        context -> {
          CoordinatorProperties props = context.getBean(CoordinatorProperties.class);
          // RFC starting points, supplied by the compact constructor when YAML binds nulls.
          assertThat(props.totalDeadline()).isEqualTo(Duration.ofSeconds(20));
          assertThat(props.defaultPerAdapterTimeout()).isEqualTo(Duration.ofSeconds(10));
          assertThat(props.perAdapterTimeout()).isEmpty();
        });
  }

  @Test
  void binding_shouldApplyDefault_whenOnlyPerAdapterMapGiven() {
    // Partial YAML: only the map is set, so total/default must still default to RFC starting
    // points.
    runner
        .withPropertyValues("integration-registry.coordinator.per-adapter-timeout.InsightIDR=15s")
        .run(
            context -> {
              CoordinatorProperties props = context.getBean(CoordinatorProperties.class);
              assertThat(props.totalDeadline()).isEqualTo(Duration.ofSeconds(20));
              assertThat(props.defaultPerAdapterTimeout()).isEqualTo(Duration.ofSeconds(10));
              assertThat(props.perAdapterTimeoutFor("InsightIDR"))
                  .isEqualTo(Duration.ofSeconds(15));
            });
  }

  @Test
  void binding_shouldFailContext_whenTotalDeadlineIsZero() {
    runner
        .withPropertyValues("integration-registry.coordinator.total-deadline=0s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("totalDeadline must be positive");
            });
  }

  @Test
  void binding_shouldFailContext_whenPerAdapterEntryIsNegative() {
    runner
        .withPropertyValues("integration-registry.coordinator.per-adapter-timeout.InsightIDR=-1s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("perAdapterTimeout.InsightIDR must be positive");
            });
  }

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

  @Configuration
  @EnableConfigurationProperties(CoordinatorProperties.class)
  static class TestConfig {}
}
