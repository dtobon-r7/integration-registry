package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Instant;
import java.util.Objects;

/**
 * A stale-tier read result: the cached {@link FetchResult} plus the timestamp the data was
 * originally fetched from the product ({@code staleSince}). The coordinator uses {@code staleSince}
 * to populate {@code stale_since} on the downstream {@code unavailable_products} entry.
 */
public record StaleEntry(FetchResult result, Instant staleSince) {

  public StaleEntry {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(staleSince, "staleSince");
  }
}
