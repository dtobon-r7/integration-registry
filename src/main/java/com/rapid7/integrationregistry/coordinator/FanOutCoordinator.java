package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.cache.StaleEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Request-time orchestration seam between the {@link IntegrationAdapter}s and the read API (T09).
 * Dispatches all registered adapters in parallel under a per-adapter timeout and a total request
 * deadline, isolates per-adapter failures, applies the stale-tier fallback decision tree against
 * the {@link IntegrationCache}, and returns a structured {@link ProductOutcome} per product.
 *
 * <p>The single invariant: one adapter's failure never fails the request (RFC-001 §Fan-out
 * coordinator). No retry — one {@code fetch} per adapter per request. Failure reasons are sourced
 * from {@link AdapterException#reasonCode()} (ADR-001), never re-derived here.
 *
 * <p>Adapters use blocking {@code RestClient} (ADR-002); a per-call virtual-thread executor makes
 * the dispatch truly concurrent. Requires {@code spring.threads.virtual.enabled=true}.
 */
@Component
public class FanOutCoordinator {

  private static final Logger log = LoggerFactory.getLogger(FanOutCoordinator.class);
  private static final String REASON_TIMEOUT = "timeout";
  private static final String REASON_NO_DATA = "no_data";

  private final Set<IntegrationAdapter> adapters;
  private final IntegrationCache cache;
  private final CoordinatorProperties properties;

  public FanOutCoordinator(
      Set<IntegrationAdapter> adapters, IntegrationCache cache, CoordinatorProperties properties) {
    this.adapters = adapters;
    this.cache = cache;
    this.properties = properties;
  }

  /**
   * Fetch every registered product's integrations for {@code orgId}, in parallel. Returns one
   * {@link ProductOutcome} per adapter; never throws for a per-adapter failure.
   */
  public List<ProductOutcome> fetchAll(String orgId, HttpHeaders authHeaders) {
    List<ProductOutcome> outcomes = new ArrayList<>();
    List<Dispatch> dispatches = new ArrayList<>();

    // Phase 1: fresh-tier reads. A fresh hit is served directly — no adapter task submitted.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (IntegrationAdapter adapter : adapters) {
        String product = adapter.productName();
        Optional<FetchResult> fresh = cache.readFresh(orgId, product);
        if (fresh.isPresent()) {
          outcomes.add(servedFresh(product, fresh.get()));
        } else {
          Future<FetchResult> future = executor.submit(() -> adapter.fetch(orgId, authHeaders));
          dispatches.add(new Dispatch(product, future));
        }
      }

      // Phase 2: await each dispatched adapter under per-adapter timeout AND total deadline.
      Instant deadline = Instant.now().plus(properties.totalDeadline());
      for (Dispatch dispatch : dispatches) {
        outcomes.add(awaitAndClassify(dispatch, orgId, deadline));
      }
    }
    return outcomes;
  }

  private ProductOutcome awaitAndClassify(Dispatch dispatch, String orgId, Instant deadline) {
    String product = dispatch.product();
    long perAdapterMs = properties.perAdapterTimeoutFor(product).toMillis();
    long untilDeadlineMs = Duration.between(Instant.now(), deadline).toMillis();
    long waitMs = Math.max(0, Math.min(perAdapterMs, untilDeadlineMs));
    try {
      FetchResult result = dispatch.future().get(waitMs, TimeUnit.MILLISECONDS);
      return classifySuccess(product, orgId, result);
    } catch (TimeoutException e) {
      dispatch.future().cancel(true);
      log.debug("Adapter {} exceeded its deadline; treating as timeout", product);
      return staleOrUnavailable(orgId, product, REASON_TIMEOUT);
    } catch (ExecutionException e) {
      return classifyFailure(orgId, product, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      dispatch.future().cancel(true);
      return staleOrUnavailable(orgId, product, REASON_TIMEOUT);
    }
  }

  private ProductOutcome classifySuccess(String product, String orgId, FetchResult result) {
    if (result.integrations().isEmpty()) {
      // Successful-but-empty: serve stale if usable, else no_data. Empty success is NOT cached.
      return staleOrUnavailable(orgId, product, REASON_NO_DATA);
    }
    cache.writeOnSuccess(orgId, product, result);
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), false, false, Optional.empty());
  }

  private ProductOutcome classifyFailure(String orgId, String product, Throwable cause) {
    if (cause instanceof AdapterException adapterException) {
      return staleOrUnavailable(orgId, product, adapterException.reasonCode());
    }
    // An unexpected (non-AdapterException) cause is an adapter-contract violation, not partial
    // unavailability — surface it so T09's @ControllerAdvice maps it to 500. Never silently
    // dropped.
    throw new IllegalStateException(
        "Adapter " + product + " failed with an unexpected exception", cause);
  }

  /** Stale-fallback decision: serve stale within window, else omit with the given reason. */
  private ProductOutcome staleOrUnavailable(String orgId, String product, String reason) {
    Optional<StaleEntry> stale = cache.readStale(orgId, product);
    if (stale.isPresent()) {
      StaleEntry entry = stale.get();
      return new ProductOutcome.Served(
          product,
          entry.result().integrations(),
          entry.result().fetchedAt(),
          false,
          true,
          Optional.of(entry.staleSince()));
    }
    return new ProductOutcome.Unavailable(product, reason, false);
  }

  private ProductOutcome servedFresh(String product, FetchResult result) {
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), true, false, Optional.empty());
  }

  private record Dispatch(String product, Future<FetchResult> future) {}
}
