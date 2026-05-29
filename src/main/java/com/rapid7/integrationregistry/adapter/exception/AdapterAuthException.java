package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the upstream returns a 4xx authentication/authorization failure (typically 401/403).
 * Mapped by the fan-out coordinator to {@code unavailable_products[].reason = "auth_failure"}
 * (RFC-001).
 *
 * <p>This is a permanent failure — {@link #isTransient()} returns {@code false} — so the
 * coordinator does NOT serve stale-tier data on this exception. See ADR-001 for the family-parent
 * rule.
 */
public class AdapterAuthException extends AdapterException {

  private static final long serialVersionUID = 1L;

  public AdapterAuthException(String message) {
    super(message);
  }

  /**
   * Use this constructor whenever a non-null underlying cause exists (e.g. {@code
   * WebClientResponseException} for a 401 response from {@code WebClient}); use {@link
   * #AdapterAuthException(String)} only for adapter-internal failures with no underlying exception.
   * Preserving the cause chain is required for downstream debugging.
   */
  public AdapterAuthException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean isTransient() {
    return false;
  }

  @Override
  public String reasonCode() {
    return "auth_failure";
  }
}
