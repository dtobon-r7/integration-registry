package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * Synthetic in-process {@link IntegrationAdapter} doubles for {@link FanOutCoordinator} tests. The
 * coordinator is adapter-agnostic, so these real (non-mock) adapters exercise dispatch, timeout,
 * and classification without any HTTP. Each tracks its invocation count so tests can assert a fresh
 * cache hit skipped the adapter call.
 */
final class CoordinatorAdapterFixtures {

  private CoordinatorAdapterFixtures() {}

  static final Instant FETCHED_AT = Instant.parse("2026-06-01T12:00:00Z");

  static FetchResult sampleResult(String productName, Instant fetchedAt) {
    NormalizedIntegration integration =
        new NormalizedIntegration(
            "id-1",
            new SourceIdentifier("plugin_name", "jira"),
            productName,
            "Automation Plugin",
            null,
            IntegrationStatus.HEALTHY,
            null,
            "https://example/integrations/id-1",
            "org-123");
    return new FetchResult(List.of(integration), fetchedAt);
  }

  /** Base double exposing a productName and an invocation counter. */
  abstract static class CountingAdapter implements IntegrationAdapter {
    final AtomicInteger calls = new AtomicInteger();
    private final String productName;

    CountingAdapter(String productName) {
      this.productName = productName;
    }

    @Override
    public String productName() {
      return productName;
    }

    int callCount() {
      return calls.get();
    }
  }

  /** Returns a non-empty result immediately. */
  static CountingAdapter success(String productName) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        return sampleResult(productName, FETCHED_AT);
      }
    };
  }

  /** Returns an empty (but successful) result. */
  static CountingAdapter empty(String productName) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        return new FetchResult(List.of(), FETCHED_AT);
      }
    };
  }

  /** Throws the supplied adapter exception. */
  static CountingAdapter throwing(String productName, AdapterException toThrow) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders)
          throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
        calls.incrementAndGet();
        switch (toThrow) {
          case AdapterTimeoutException e -> throw e;
          case AdapterAuthException e -> throw e;
          case AdapterUpstreamException e -> throw e;
          default -> throw new IllegalStateException("unexpected adapter exception type");
        }
      }
    };
  }

  /** Sleeps {@code sleepMillis} before returning a result — used to trip per-adapter timeouts. */
  static CountingAdapter slow(String productName, long sleepMillis) {
    return new CountingAdapter(productName) {
      @Override
      public FetchResult fetch(String orgId, HttpHeaders authHeaders) {
        calls.incrementAndGet();
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("interrupted", e);
        }
        return sampleResult(productName, FETCHED_AT);
      }
    };
  }
}
