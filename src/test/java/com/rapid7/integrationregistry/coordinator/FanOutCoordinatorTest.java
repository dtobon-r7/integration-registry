package com.rapid7.integrationregistry.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
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

  @Test
  void fetchAll_shouldFetchWriteAndServe_whenFreshMissAndAdapterSucceeds() {
    // Arrange: fresh + stale miss (defaults), adapter returns data
    var adapter = CoordinatorAdapterFixtures.success("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: adapter called once, fresh tier written, served non-stale non-cache-hit
    assertThat(adapter.callCount()).isEqualTo(1);
    verify(cache).writeOnSuccess(eq(ORG), eq("InsightConnect"), any(FetchResult.class));
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.cacheHitPerProduct()).isFalse();
              assertThat(served.stale()).isFalse();
              assertThat(served.integrations()).hasSize(1);
            });
  }

  @Test
  void fetchAll_shouldMapTimeoutException_toTimeoutReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterTimeoutException("boom"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class, u -> assertThat(u.reason()).isEqualTo("timeout"));
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldMapUpstreamException_toUpstream5xxReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterUpstreamException("503"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("upstream_5xx"));
  }

  @Test
  void fetchAll_shouldMapAuthException_toAuthFailureReason() {
    // Arrange
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterAuthException("401"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class,
            u -> assertThat(u.reason()).isEqualTo("auth_failure"));
  }

  @Test
  void fetchAll_shouldReturnNoData_whenEmptySuccessAndNoStale() {
    // Arrange: adapter returns empty, no stale entry
    var adapter = CoordinatorAdapterFixtures.empty("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: no_data, and the empty success is NOT written to cache
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class, u -> assertThat(u.reason()).isEqualTo("no_data"));
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldServeStale_whenAdapterFailsAndStaleWithinWindow() {
    // Arrange: adapter times out, stale entry exists
    Instant staleSince = Instant.parse("2026-05-31T09:00:00Z");
    FetchResult staleResult = CoordinatorAdapterFixtures.sampleResult("InsightIDR", staleSince);
    when(cache.readStale(ORG, "InsightIDR"))
        .thenReturn(Optional.of(new StaleEntry(staleResult, staleSince)));
    var adapter =
        CoordinatorAdapterFixtures.throwing("InsightIDR", new AdapterUpstreamException("503"));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: served stale with staleSince populated; never overwrites the stale tier
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class,
            served -> {
              assertThat(served.stale()).isTrue();
              assertThat(served.staleSince()).contains(staleSince);
              assertThat(served.cacheHitPerProduct()).isFalse();
              assertThat(served.integrations()).hasSize(1);
            });
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldServeStale_whenEmptySuccessAndStaleWithinWindow() {
    // Arrange: adapter returns empty, but a usable stale entry exists
    Instant staleSince = Instant.parse("2026-05-31T09:00:00Z");
    FetchResult staleResult = CoordinatorAdapterFixtures.sampleResult("InsightConnect", staleSince);
    when(cache.readStale(ORG, "InsightConnect"))
        .thenReturn(Optional.of(new StaleEntry(staleResult, staleSince)));
    var adapter = CoordinatorAdapterFixtures.empty("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: empty-success falls back to stale rather than no_data
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class, served -> assertThat(served.stale()).isTrue());
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void fetchAll_shouldTimeOutSlowAdapter_whenPerAdapterTimeoutExceeded() {
    // Arrange: a 500ms-slow adapter with a 100ms per-adapter timeout, no stale → timeout/omitted
    var slow = CoordinatorAdapterFixtures.slow("InsightIDR", 500);
    CoordinatorProperties tightProps =
        new CoordinatorProperties(
            Duration.ofSeconds(20),
            Duration.ofMillis(100),
            Map.of("InsightIDR", Duration.ofMillis(100)));
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(slow), cache, tightProps);

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: surfaced as a timeout, not a hang
    assertThat(outcomes.get(0))
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class, u -> assertThat(u.reason()).isEqualTo("timeout"));
  }

  @Test
  void fetchAll_shouldPreserveFastAdapter_whenTotalDeadlineCutsOffSlowOne() {
    // Arrange: total deadline 200ms. Fast adapter succeeds; slow adapter (800ms) is cut off.
    // Generous per-adapter timeouts so the TOTAL deadline is the binding constraint.
    var fast = CoordinatorAdapterFixtures.success("InsightConnect");
    var slow = CoordinatorAdapterFixtures.slow("InsightIDR", 800);
    CoordinatorProperties deadlineProps =
        new CoordinatorProperties(Duration.ofMillis(200), Duration.ofSeconds(30), Map.of());
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(fast, slow)), cache, deadlineProps);

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: both products present; fast served, slow omitted as timeout
    assertThat(outcomes).hasSize(2);
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightConnect"))
        .first()
        .isInstanceOf(ProductOutcome.Served.class);
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightIDR"))
        .first()
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class, u -> assertThat(u.reason()).isEqualTo("timeout"));
  }

  @Test
  void fetchAll_shouldDispatchAdaptersConcurrently_notSerially() {
    // Arrange: two adapters each sleeping 300ms. Serial would be ~600ms; concurrent ~300ms.
    var a = CoordinatorAdapterFixtures.slow("InsightConnect", 300);
    var b = CoordinatorAdapterFixtures.slow("InsightIDR", 300);
    // Per-adapter + total budgets comfortably above 300ms so neither times out.
    CoordinatorProperties roomyProps =
        new CoordinatorProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), Map.of());
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(a, b)), cache, roomyProps);

    // Act
    long start = System.nanoTime();
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // Assert: both served, and wall-time is far closer to one sleep than two (proves concurrency).
    assertThat(outcomes).hasSize(2).allMatch(o -> o instanceof ProductOutcome.Served);
    assertThat(elapsedMs).isLessThan(550);
  }

  @Test
  void fetchAll_shouldCancelSiblings_whenAnAdapterThrowsUnexpectedly() {
    // Arrange: one adapter throws a plain RuntimeException (non-AdapterException → contract
    // violation), one adapter is slow (would sleep 5s). Generous budgets so neither times out.
    var boom = CoordinatorAdapterFixtures.throwingRuntime("InsightConnect", "kaboom");
    var slow = CoordinatorAdapterFixtures.recordingSlow("InsightIDR", 5_000);
    CoordinatorProperties roomyProps =
        new CoordinatorProperties(Duration.ofSeconds(30), Duration.ofSeconds(30), Map.of());
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(boom, slow)), cache, roomyProps);

    // Act + Assert: the contract violation propagates, and it does so well under the 5s sleep,
    // proving the slow sibling was cancelled rather than awaited to completion.
    long start = System.nanoTime();
    assertThatThrownBy(() -> coordinator.fetchAll(ORG, new HttpHeaders()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("InsightConnect");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isLessThan(2_000);
    assertThat(slow.wasInterrupted()).isTrue();
  }

  @Test
  void fetchAll_shouldThrow_whenAdapterReturnsNullFetchResult() {
    // Arrange: adapter returns null from fetch() — an adapter-contract violation.
    var adapter = CoordinatorAdapterFixtures.nullReturning("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act + Assert: surfaced as IllegalStateException naming the product, routed to T09's 500.
    assertThatThrownBy(() -> coordinator.fetchAll(ORG, new HttpHeaders()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("InsightConnect")
        .hasMessageContaining("null FetchResult");
    verify(cache, never()).writeOnSuccess(anyString(), anyString(), any());
  }

  @Test
  void constructor_shouldThrow_whenTwoAdaptersShareProductName() {
    // Arrange: two distinct adapter instances claiming the same productName().
    var first = CoordinatorAdapterFixtures.success("InsightConnect");
    var second = CoordinatorAdapterFixtures.empty("InsightConnect");

    // Act + Assert: fail fast at construction; the cache must not be touched.
    assertThatThrownBy(
            () ->
                new FanOutCoordinator(
                    new java.util.LinkedHashSet<>(java.util.List.of(first, second)),
                    cache,
                    props()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("InsightConnect");
  }

  @Test
  void fetchAll_shouldThrow_whenOrgIdBlank() {
    // Arrange
    var adapter = CoordinatorAdapterFixtures.success("InsightConnect");
    FanOutCoordinator coordinator = new FanOutCoordinator(Set.of(adapter), cache, props());

    // Act + Assert: a blank orgId fails clearly at the boundary, before any cache call.
    assertThatThrownBy(() -> coordinator.fetchAll("   ", new HttpHeaders()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(cache, never()).readFresh(anyString(), anyString());
  }

  @Test
  void fetchAll_shouldIsolateTimeout_whenOneAdapterTimesOutAndOtherSucceeds() {
    // Acceptance signal #1: one adapter times out, the other returns data; the request does not
    // throw and both products are present with the expected outcomes.
    var fast = CoordinatorAdapterFixtures.success("InsightConnect");
    var slow = CoordinatorAdapterFixtures.slow("InsightIDR", 500);
    CoordinatorProperties isoProps =
        new CoordinatorProperties(
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Map.of("InsightIDR", Duration.ofMillis(100)));
    FanOutCoordinator coordinator =
        new FanOutCoordinator(
            new java.util.LinkedHashSet<>(java.util.List.of(fast, slow)), cache, isoProps);

    // Act
    List<ProductOutcome> outcomes = coordinator.fetchAll(ORG, new HttpHeaders());

    // Assert: no throw; both products present; InsightConnect served, InsightIDR timeout.
    assertThat(outcomes).hasSize(2);
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightConnect"))
        .first()
        .isInstanceOfSatisfying(
            ProductOutcome.Served.class, s -> assertThat(s.integrations()).hasSize(1));
    assertThat(outcomes)
        .filteredOn(o -> o.productName().equals("InsightIDR"))
        .first()
        .isInstanceOfSatisfying(
            ProductOutcome.Unavailable.class, u -> assertThat(u.reason()).isEqualTo("timeout"));
  }
}
