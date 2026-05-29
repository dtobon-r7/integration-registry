package com.rapid7.integrationregistry.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FetchResultTest {

  private static final NormalizedIntegration SAMPLE =
      new NormalizedIntegration(
          "c_456",
          new SourceIdentifier("plugin_name", "jira"),
          "InsightConnect",
          "Automation Plugin",
          null,
          IntegrationStatus.HEALTHY,
          Instant.parse("2026-05-26T10:00:00Z"),
          "https://icon.example/automation/connections/c_456",
          "org_abc");

  private static final Instant FETCHED_AT = Instant.parse("2026-05-26T10:00:00Z");

  @Test
  void constructor_shouldBuildRecord_whenIntegrationsListAndTimestampProvided() {
    // Arrange
    List<NormalizedIntegration> integrations = List.of(SAMPLE);

    // Act
    FetchResult result = new FetchResult(integrations, FETCHED_AT);

    // Assert
    assertThat(result.integrations()).containsExactly(SAMPLE);
    assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
  }

  @Test
  void constructor_shouldAcceptEmptyList_whenNoIntegrationsReturned() {
    // Arrange
    List<NormalizedIntegration> empty = List.of();

    // Act
    FetchResult result = new FetchResult(empty, FETCHED_AT);

    // Assert
    assertThat(result.integrations()).isEmpty();
    assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
  }

  @Test
  void constructor_shouldThrowNPE_whenIntegrationsListNull() {
    // Act + Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new FetchResult(null, FETCHED_AT))
        .withMessage(FetchResult.FIELD_INTEGRATIONS);
  }

  @Test
  void constructor_shouldThrowNPE_whenFetchedAtNull() {
    // Arrange
    List<NormalizedIntegration> integrations = List.of(SAMPLE);

    // Act + Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new FetchResult(integrations, null))
        .withMessage(FetchResult.FIELD_FETCHED_AT);
  }

  @Test
  void integrations_shouldBeImmutable_whenSourceListMutatedAfterConstruction() {
    // Arrange
    List<NormalizedIntegration> mutable = new ArrayList<>();
    mutable.add(SAMPLE);
    FetchResult result = new FetchResult(mutable, FETCHED_AT);

    // Act
    mutable.clear();

    // Assert — record's view is unaffected by post-construction mutation of the source list
    assertThat(result.integrations()).containsExactly(SAMPLE);
  }
}
