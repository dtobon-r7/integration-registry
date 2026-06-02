package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 * deadline, isolates per-adapter failures, and delegates the stale-tier fallback decision tree and
 * all {@link ProductOutcome} construction to the {@link OutcomeClassifier}, returning a structured
 * {@link ProductOutcome} per product.
 *
 * <p>The single invariant: one adapter's failure never fails the request (RFC-001 §Fan-out
 * coordinator). No retry — one {@code fetch} per adapter per request. Failure reasons are sourced
 * from {@code AdapterException.reasonCode()} (ADR-001) by the classifier, never re-derived here.
 *
 * <p>Adapters use blocking {@code RestClient} (ADR-002); a per-call virtual-thread executor makes
 * the dispatch truly concurrent. Requires {@code spring.threads.virtual.enabled=true}.
 */
// CouplingBetweenObjects: the review must-fixes added two unavoidable JDK exception types —
// IllegalStateException (null-FetchResult and duplicate-productName contract violations) and
// IllegalArgumentException (orgId boundary guard) — pushing CBO from 15 to 17. Both are mandated
// by the input-validation contract and cannot be folded away without re-introducing the very bug
// they guard. Suppressed locally and justified rather than weakening the project-wide threshold,
// mirroring the existing precedent on VendorAggregator.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
public class FanOutCoordinator {

  private static final Logger log = LoggerFactory.getLogger(FanOutCoordinator.class);

  private final Set<IntegrationAdapter> adapters;
  private final IntegrationCache cache;
  private final CoordinatorProperties properties;
  private final OutcomeClassifier classifier;

  public FanOutCoordinator(
      Set<IntegrationAdapter> adapters, IntegrationCache cache, CoordinatorProperties properties) {
    requireUniqueProductNames(adapters);
    this.adapters = adapters;
    this.cache = cache;
    this.properties = properties;
    this.classifier = new OutcomeClassifier(cache);
  }

  /**
   * Fail fast at startup if two registered adapters claim the same {@code productName()}:
   * duplicates would otherwise produce duplicate {@link ProductOutcome}s and a same-key cache write
   * race at request time.
   */
  private static void requireUniqueProductNames(Set<IntegrationAdapter> adapters) {
    Set<String> seen = new HashSet<>();
    List<String> duplicates =
        adapters.stream()
            .map(IntegrationAdapter::productName)
            .filter(product -> !seen.add(product))
            .toList();
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException("Duplicate adapter productName(s) registered: " + duplicates);
    }
  }

  /**
   * Fetch every registered product's integrations for {@code orgId}, in parallel. Returns one
   * {@link ProductOutcome} per adapter; never throws for a per-adapter failure.
   */
  public List<ProductOutcome> fetchAll(String orgId, HttpHeaders authHeaders) {
    if (orgId == null || orgId.isBlank()) {
      throw new IllegalArgumentException("orgId must not be null or blank");
    }

    List<ProductOutcome> outcomes = new ArrayList<>();
    List<Dispatch> dispatches = new ArrayList<>();

    // Phase 1: fresh-tier reads. A fresh hit is served directly — no adapter task submitted.
    // try-with-resources closes the executor, which blocks until all submitted tasks terminate.
    // On the timeout branch tasks are cancel(true)'d (interrupt). The total-deadline guarantee
    // therefore assumes adapter tasks are interruptible — the real RestClient adapters use
    // interruptible socket I/O (ADR-002), so an interrupt promptly unblocks them. A task that
    // ignored interrupts could delay close() past the deadline.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (IntegrationAdapter adapter : adapters) {
        String product = adapter.productName();
        Optional<FetchResult> fresh = cache.readFresh(orgId, product);
        if (fresh.isPresent()) {
          outcomes.add(classifier.servedFresh(product, fresh.get()));
        } else {
          Future<FetchResult> future = executor.submit(() -> adapter.fetch(orgId, authHeaders));
          dispatches.add(new Dispatch(product, future));
        }
      }

      // Phase 2: await each dispatched adapter under per-adapter timeout AND total deadline.
      // A non-AdapterException cause (or a null FetchResult) makes awaitAndClassify throw
      // IllegalStateException — a contract violation that propagates to T09's 500. If it escapes
      // mid-loop, the remaining, un-awaited futures would still be running and executor.close()
      // would block on them past the deadline. The finally cancels every not-yet-done dispatch so
      // close() only waits on cancelled/interruptible tasks and the original exception propagates.
      Instant deadline = Instant.now().plus(properties.totalDeadline());
      try {
        for (Dispatch dispatch : dispatches) {
          outcomes.add(awaitAndClassify(dispatch, orgId, deadline));
        }
      } finally {
        for (Dispatch dispatch : dispatches) {
          dispatch.future().cancel(true);
        }
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
      if (result == null) {
        // A null return is an adapter-contract violation, not partial unavailability — surface it
        // the same way as a non-AdapterException cause (Fix 1's cancellation path → T09's 500).
        // Never silently coerced to Unavailable.
        throw new IllegalStateException("Adapter " + product + " returned a null FetchResult");
      }
      return classifier.classifySuccess(orgId, product, result);
    } catch (TimeoutException e) {
      dispatch.future().cancel(true);
      // Distinguish the binding budget: if the total-request deadline left less headroom than the
      // per-adapter timeout, the deadline was exhausted; otherwise the adapter ran past its own
      // per-adapter timeout. Both classify as a transient "timeout".
      String boundBy =
          untilDeadlineMs < perAdapterMs ? "total request deadline" : "per-adapter timeout";
      log.debug(
          "Adapter {} timed out after {}ms (bound by {}); treating as timeout",
          product,
          waitMs,
          boundBy);
      return classifier.onTransientTimeout(orgId, product);
    } catch (ExecutionException e) {
      return classifier.classifyFailure(orgId, product, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      dispatch.future().cancel(true);
      return classifier.onTransientTimeout(orgId, product);
    }
  }

  private record Dispatch(String product, Future<FetchResult> future) {}
}
