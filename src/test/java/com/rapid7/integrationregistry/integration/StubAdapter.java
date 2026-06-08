package com.rapid7.integrationregistry.integration;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * Hand-written {@link IntegrationAdapter} double for the WP-04 full-context suite. The {@code
 * productName} is hard-coded (set at construction) so it survives {@code FanOutCoordinator}'s
 * constructor-time product-name validation at boot — a Mockito mock's {@code productName()} would
 * be null there and fail boot. {@code fetch} delegates to a per-test-settable {@link Behavior};
 * tests call {@link #willReturn}/{@link #willThrow}/{@link #reset} between scenarios. Tracks an
 * invocation count so a test can assert a fresh cache hit skipped the adapter call.
 *
 * <p>Distinct from {@code CoordinatorAdapterFixtures.CountingAdapter} (the coordinator-package
 * double) because this one is <em>mutable across scenarios</em>: it lives as a long-lived singleton
 * bean in a cached {@code @SpringBootTest} context, so its behavior is reconfigured per test rather
 * than fixed at construction. That mutability — not the hard-coded {@code productName} — is why a
 * separate double exists.
 */
final class StubAdapter implements IntegrationAdapter {

  /** A fetch behavior that may throw any of the declared adapter exceptions. */
  @FunctionalInterface
  interface Behavior {
    FetchResult apply(String orgId, HttpHeaders headers)
        throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException;
  }

  private final String productName;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile Behavior behavior;

  StubAdapter(String productName) {
    this.productName = productName;
    this.behavior = defaultBehavior();
  }

  private static Behavior defaultBehavior() {
    return (orgId, headers) -> {
      throw new IllegalStateException(
          "StubAdapter behavior not configured for this scenario; call willReturn/willThrow");
    };
  }

  /** Configure this adapter to return {@code result} on the next fetch(es). */
  void willReturn(FetchResult result) {
    this.behavior = (orgId, headers) -> result;
  }

  /** Configure this adapter to throw {@code toThrow} on the next fetch(es). */
  void willThrow(AdapterException toThrow) {
    this.behavior =
        (orgId, headers) -> {
          switch (toThrow) {
            case AdapterTimeoutException e -> throw e;
            case AdapterAuthException e -> throw e;
            case AdapterUpstreamException e -> throw e;
            default -> throw new IllegalStateException("unexpected adapter exception type");
          }
        };
  }

  /** Reset call count and behavior between scenarios. */
  void reset() {
    this.calls.set(0);
    this.behavior = defaultBehavior();
  }

  int callCount() {
    return calls.get();
  }

  @Override
  public String productName() {
    return productName;
  }

  @Override
  public FetchResult fetch(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
    calls.incrementAndGet();
    return behavior.apply(orgId, authHeaders);
  }
}
