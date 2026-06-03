package com.rapid7.integrationregistry.coordinator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured per-product result of a fan-out dispatch — the stagger-point contract T09's {@code
 * VendorService} assembles {@code unavailable_products[]} and {@code metadata} from (RFC-001
 * §Supporting types).
 *
 * <p>Stale data is carried on {@link Served} with {@code stale == true} (a stale serve still
 * contributes integrations to the grid); {@link Unavailable} is the genuine omission case. T09
 * computes {@code metadata.cache_hit} as true iff every outcome is a {@code Served} with {@code
 * cacheHitPerProduct == true}, and {@code metadata.as_of} as the oldest {@code fetchedAt} across
 * {@code Served} outcomes.
 */
public sealed interface ProductOutcome permits ProductOutcome.Served, ProductOutcome.Unavailable {

  /** The canonical {@code productName()} of the adapter that produced this outcome. */
  String productName();

  /**
   * A product whose integrations are present in the response — served either fresh (from the
   * adapter or the fresh cache tier) or stale (from the stale tier on adapter failure).
   *
   * @param cacheHitPerProduct true only on a fresh-tier hit (no adapter call this request)
   * @param stale true when served from the stale tier; {@code staleSince} is then present
   * @param staleSince the original product fetch time of the stale data; present iff {@code stale}
   */
  record Served(
      String productName,
      List<NormalizedIntegration> integrations,
      Instant fetchedAt,
      boolean cacheHitPerProduct,
      boolean stale,
      Optional<Instant> staleSince)
      implements ProductOutcome {

    public Served {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(integrations, "integrations");
      Objects.requireNonNull(fetchedAt, "fetchedAt");
      Objects.requireNonNull(staleSince, "staleSince");
      if (stale != staleSince.isPresent()) {
        throw new IllegalArgumentException("staleSince must be present iff stale is true");
      }
      integrations = List.copyOf(integrations);
    }
  }

  /**
   * A product omitted from the response: the adapter failed (timeout, upstream_5xx, auth_failure)
   * or returned empty ({@code no_data}) and no usable stale entry existed. There is no stale flag
   * here — a stale serve is, by definition, a {@link Served} with {@code stale == true}; {@code
   * Unavailable} is always the genuine omission case, so T09 reads staleness off {@code Served}
   * only.
   *
   * @param reason one of {@code timeout | upstream_5xx | auth_failure | no_data} (RFC-001
   *     §Supporting types); for adapter exceptions this is {@code AdapterException.reasonCode()},
   *     never re-derived at the catch site (ADR-001)
   */
  record Unavailable(String productName, String reason) implements ProductOutcome {

    public Unavailable {
      Objects.requireNonNull(productName, "productName");
      Objects.requireNonNull(reason, "reason");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }
}
