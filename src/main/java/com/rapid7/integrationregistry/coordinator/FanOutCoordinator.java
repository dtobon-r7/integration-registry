package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
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
 * <p>Each adapter's whole pipeline — fresh-tier read, {@code fetch}, and the success/stale
 * classification (including the cache write and any stale read) — runs as a single task on a
 * per-call virtual-thread executor, so all of that cache I/O is bounded by the dispatch's {@code
 * future.get} budget rather than running serially on the request thread. The one exception is the
 * stale read on the timeout path: it necessarily runs on the request thread, because the task is
 * still hung and has been cancelled. Adapters use blocking {@code RestClient} (ADR-002); the
 * virtual-thread executor makes the dispatch truly concurrent. Parallelism is created directly by
 * this executor and does not depend on {@code spring.threads.virtual.enabled} — that property is
 * recommended for the surrounding request-handling threads, not a hard requirement here.
 */
// CouplingBetweenObjects: the input-validation and classification contract pulls in JDK exception
// types — IllegalStateException (null-FetchResult and bad-productName contract violations) and
// IllegalArgumentException (orgId boundary guards) — plus AdapterException (caught inside the
// dispatch task so transient-failure stale reads stay off the request thread), pushing CBO past the
// project threshold of 15. These are mandated by the contract and cannot be folded away without
// re-introducing the very bugs they guard. Suppressed locally and justified rather than weakening
// the project-wide threshold, mirroring the existing precedent on VendorAggregator.
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
    validateProductNames(adapters);
    this.adapters = adapters;
    this.cache = cache;
    this.properties = properties;
    this.classifier = new OutcomeClassifier(cache);
  }

  /**
   * Fail fast at startup if any registered adapter has a null/blank {@code productName()} (it would
   * later blow up in the cache key builder at request time) or if two adapters claim the same
   * {@code productName()} (duplicates would produce duplicate {@link ProductOutcome}s and a
   * same-key cache write race). Boot-time rejection beats a per-request NPE.
   */
  private static void validateProductNames(Set<IntegrationAdapter> adapters) {
    Set<String> seen = new HashSet<>();
    for (IntegrationAdapter adapter : adapters) {
      String product = adapter.productName();
      if (product == null || product.isBlank()) {
        throw new IllegalStateException(
            "Adapter " + adapter.getClass().getName() + " has a null or blank productName()");
      }
      if (!seen.add(product)) {
        throw new IllegalStateException("Duplicate adapter productName registered: " + product);
      }
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
    // The cache key builder reserves ':' as its segment delimiter (CacheKey). Reject it at the
    // boundary so a malformed orgId fails clearly here rather than deep inside the cache read path.
    if (orgId.contains(":")) {
      throw new IllegalArgumentException("orgId must not contain ':'");
    }

    List<Dispatch> dispatches = new ArrayList<>();

    // Each adapter's whole pipeline runs as one task: fresh read, fetch, then classify (cache write
    // or stale read included). try-with-resources closes the executor, blocking until every task
    // terminates; on timeout/cancel the tasks are cancel(true)'d. The total-deadline guarantee thus
    // assumes interruptible tasks — the real RestClient adapters use interruptible socket I/O
    // (ADR-002), so an interrupt unblocks them promptly. A task that ignored interrupts could delay
    // close() past the deadline.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (IntegrationAdapter adapter : adapters) {
        Future<ProductOutcome> future =
            executor.submit(() -> fetchOne(adapter, orgId, authHeaders));
        dispatches.add(new Dispatch(adapter.productName(), future));
      }
      return awaitAll(dispatches, orgId);
    }
  }

  /**
   * Await every dispatch under the per-adapter timeout AND the total deadline, classifying each. A
   * contract violation (null FetchResult, or a non-AdapterException from the adapter) surfaces as
   * an IllegalStateException routed to T09's 500; if it escapes mid-loop the remaining futures
   * would still be running and the caller's executor.close() would block on them past the deadline,
   * so the finally cancels every dispatch (a no-op on already-finished ones) before returning.
   */
  private List<ProductOutcome> awaitAll(List<Dispatch> dispatches, String orgId) {
    List<ProductOutcome> outcomes = new ArrayList<>();
    Instant deadline = Instant.now().plus(properties.totalDeadline());
    boolean interrupted = false;
    try {
      for (Dispatch dispatch : dispatches) {
        try {
          outcomes.add(awaitAndClassify(dispatch, orgId, deadline));
        } catch (InterruptedException e) {
          // get() throwing already CLEARED the interrupt flag. Classify the timeout now, while the
          // flag is clear, so the classifier's blocking stale read is not fast-failed by a pending
          // interrupt; restore the flag once after the loop (not per-dispatch, which would force
          // every later dispatch's stale read to run with the flag set).
          dispatch.future().cancel(true);
          outcomes.add(classifier.onTransientTimeout(orgId, dispatch.product()));
          interrupted = true;
        }
      }
    } finally {
      for (Dispatch dispatch : dispatches) {
        dispatch.future().cancel(true);
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return outcomes;
  }

  /**
   * The per-adapter dispatch task: a fresh hit short-circuits the adapter call; otherwise fetch and
   * classify. Transient failures read/serve stale HERE, inside the task, so that stale read is
   * bounded by the dispatch future's get() budget rather than running on the request thread. A null
   * result or a non-{@link AdapterException} escapes as an unchecked exception (a contract
   * violation), surfacing via {@link ExecutionException} at the await site.
   */
  private ProductOutcome fetchOne(
      IntegrationAdapter adapter, String orgId, HttpHeaders authHeaders) {
    String product = adapter.productName();
    Optional<FetchResult> fresh = cache.readFresh(orgId, product);
    if (fresh.isPresent()) {
      return classifier.servedFresh(product, fresh.get());
    }
    try {
      FetchResult result = adapter.fetch(orgId, authHeaders);
      if (result == null) {
        throw new IllegalStateException("Adapter " + product + " returned a null FetchResult");
      }
      return classifier.classifySuccess(orgId, product, result);
    } catch (AdapterException e) {
      return classifier.classifyFailure(orgId, product, e);
    }
  }

  private ProductOutcome awaitAndClassify(Dispatch dispatch, String orgId, Instant deadline)
      throws InterruptedException {
    String product = dispatch.product();
    long perAdapterMs = properties.perAdapterTimeoutFor(product).toMillis();
    long untilDeadlineMs = Duration.between(Instant.now(), deadline).toMillis();
    long waitMs = Math.max(0, Math.min(perAdapterMs, untilDeadlineMs));
    try {
      return dispatch.future().get(waitMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      dispatch.future().cancel(true);
      log.debug(
          "Adapter {} did not complete within {}ms (per-adapter {}ms, deadline-remaining {}ms);"
              + " treating as timeout",
          product,
          waitMs,
          perAdapterMs,
          untilDeadlineMs);
      // The only cache call not bounded by a dispatch task's get() budget: the stale read on the
      // timeout path runs here on the request thread because the task is hung and was cancelled.
      return classifier.onTransientTimeout(orgId, product);
    } catch (ExecutionException e) {
      throw taskContractViolation(product, e.getCause());
    }
  }

  /**
   * A dispatch task escapes with an exception only on a contract violation: a null {@link
   * FetchResult} (an {@link IllegalStateException} raised in-task) or a non-{@link
   * AdapterException} thrown by the adapter ({@link AdapterException}s are caught and classified
   * inside the task). The result is always an {@link IllegalStateException} that T09's
   * {@code @ControllerAdvice} maps to a 500 — the violation is never silently coerced to {@link
   * ProductOutcome.Unavailable}. Returns the exception (rather than throwing) so the call site
   * reads {@code throw taskContractViolation(...)}, making clear the dispatch yields no outcome.
   */
  private IllegalStateException taskContractViolation(String product, Throwable cause) {
    // Pass an in-task IllegalStateException (e.g. the null-FetchResult guard) through unwrapped so
    // its product-naming message survives; wrap anything else.
    if (cause instanceof IllegalStateException ise) {
      return ise;
    }
    return new IllegalStateException(
        "Adapter " + product + " failed with an unexpected exception", cause);
  }

  private record Dispatch(String product, Future<ProductOutcome> future) {}
}
