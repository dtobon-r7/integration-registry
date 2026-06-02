package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.cache.StaleEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class FanOutCoordinatorTest {

  private static final String ORG = "org-123";
  private static final Instant FETCHED_AT = CoordinatorAdapterFixtures.FETCHED_AT;

  private IntegrationCache cache;

  @BeforeEach
  void setUp() {
    cache = mock(IntegrationCache.class);
    // Default: every fresh/stale read is a miss unless a test overrides it.
    when(cache.readFresh(anyString(), anyString())).thenReturn(Optional.empty());
    when(cache.readStale(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private CoordinatorProperties props() {
    return new CoordinatorProperties(Duration.ofSeconds(20), Duration.ofSeconds(10), Map.of());
  }

  @Test
  void fetchAll_shouldServeFromFreshTier_withoutCallingAdapter() {
    // Arrange: fresh hit for InsightConnect
    FetchResult cached = CoordinatorAdapterFixtures.sampleResult("InsightConnect", FETCHED_AT);
    when(cache.readFresh(ORG, "InsightConnect")).thenReturn(Optional.of(cached));
    var adapter = CoordinatorAdapterFixtures.success("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: served fresh, adapter never invoked, cache-hit flag set
    assertThat(adapter.callCount()).isZero();
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.productName()).isEqualTo("InsightConnect");
              assertThat(served.cacheHitPerProduct()).isTrue();
              assertThat(served.stale()).isFalse();
              assertThat(served.fetchedAt()).isEqualTo(FETCHED_AT);
            });
  }

  @Test
  void fetchAll_shouldOmitNotServeStale_whenAuthFailsEvenWithStalePresent() {
    // Arrange: a usable stale entry exists, but the adapter fails with a PERMANENT auth failure.
    // Per ADR-001 / RFC-001, permanent failures (isTransient() == false) must NOT serve stale.
    Instant staleSince = FETCHED_AT.minus(Duration.ofHours(1));
    FetchResult staleResult = CoordinatorAdapterFixtures.sampleResult("InsightIDR", staleSince);
    when(cache.readStale(ORG, "InsightIDR"))
        .thenReturn(Optional.of(new StaleEntry(staleResult, staleSince)));
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterAuthException("401"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: omitted as Unavailable with reason auth_failure — stale tier is NOT served.
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            unavailable -> {
              assertThat(unavailable.productName()).isEqualTo("InsightIDR");
              assertThat(unavailable.reason()).isEqualTo("auth_failure");
              assertThat(unavailable.stale()).isFalse();
            });
  }
}
