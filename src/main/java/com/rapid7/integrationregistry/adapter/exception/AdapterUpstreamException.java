package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the upstream returns a 5xx error or the adapter classifies a transport error as
 * 'upstream broken'. Mapped by the fan-out coordinator to {@code unavailable_products[].reason =
 * "upstream_5xx"} (RFC-001).
 *
 * <p>This is a transient failure — {@link #isTransient()} returns {@code true} — so the coordinator
 * MAY serve stale-tier data on this exception within the stale window. See ADR-001 for the
 * family-parent rule.
 */
public class AdapterUpstreamException extends AdapterException {

  private static final long serialVersionUID = 1L;

  public AdapterUpstreamException(String message) {
    super(message);
  }

  /**
   * Use this constructor whenever a non-null underlying cause exists (e.g. {@code
   * WebClientResponseException} for a 503); use {@link #AdapterUpstreamException(String)} only for
   * adapter-internal failures with no underlying exception. Preserving the cause chain is required
   * for downstream debugging.
   */
  public AdapterUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean isTransient() {
    return true;
  }

  @Override
  public String reasonCode() {
    return "upstream_5xx";
  }
}
