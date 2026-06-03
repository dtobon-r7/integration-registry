package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.cache.IntegrationCache;
import com.rapid7.integrationregistry.cache.StaleEntry;
import java.util.Optional;

/**
 * Classifies the terminal state of a single adapter dispatch into a {@link ProductOutcome}, owning
 * every {@code ProductOutcome} construction and the stale-tier fallback decision tree against the
 * {@link IntegrationCache}. Extracted from {@link FanOutCoordinator} so the coordinator keeps only
 * the dispatch mechanics (executor/futures/timeout/deadline) and this collaborator keeps only the
 * outcome-classification responsibility (RFC-001 §Fan-out coordinator).
 *
 * <p>Failure reasons are sourced from {@link AdapterException#reasonCode()} (ADR-001), never
 * re-derived here.
 */
class OutcomeClassifier {

  private static final String REASON_TIMEOUT = "timeout";
  private static final String REASON_NO_DATA = "no_data";

  private final IntegrationCache cache;

  OutcomeClassifier(IntegrationCache cache) {
    this.cache = cache;
  }

  ProductOutcome classifySuccess(String orgId, String product, FetchResult result) {
    if (result.integrations().isEmpty()) {
      // Successful-but-empty: serve stale if usable, else no_data. Empty success is NOT cached.
      return staleOrUnavailable(orgId, product, REASON_NO_DATA);
    }
    cache.writeOnSuccess(orgId, product, result);
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), false, false, Optional.empty());
  }

  ProductOutcome classifyFailure(String orgId, String product, AdapterException failure) {
    // Stale-tier fallback is scoped to transient failures (timeout, upstream_5xx) per ADR-001 and
    // RFC-001 §Stale-tier fallback. A PERMANENT failure (auth_failure) must omit the product
    // outright — never read or serve stale. reason is always sourced from reasonCode(), never
    // re-derived from the exception class.
    if (failure.isTransient()) {
      return staleOrUnavailable(orgId, product, failure.reasonCode());
    }
    return new ProductOutcome.Unavailable(product, failure.reasonCode());
  }

  /** Terminal state for a timed-out or interrupted dispatch: transient, classified as timeout. */
  ProductOutcome onTransientTimeout(String orgId, String product) {
    return staleOrUnavailable(orgId, product, REASON_TIMEOUT);
  }

  ProductOutcome servedFresh(String product, FetchResult result) {
    return new ProductOutcome.Served(
        product, result.integrations(), result.fetchedAt(), true, false, Optional.empty());
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
    return new ProductOutcome.Unavailable(product, reason);
  }
}
