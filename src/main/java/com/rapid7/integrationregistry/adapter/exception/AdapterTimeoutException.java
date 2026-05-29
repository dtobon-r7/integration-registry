package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the adapter's own timeout fires (the upstream did not respond in
 * time). Mapped by the fan-out coordinator to
 * {@code unavailable_products[].reason = "timeout"} (RFC-001).
 *
 * <p>This is a transient failure — {@link #isTransient()} returns {@code true}
 * — so the coordinator MAY serve stale-tier data on this exception within the
 * stale window. See ADR-001 for the family-parent rule.
 */
public class AdapterTimeoutException extends AdapterException {

    private static final long serialVersionUID = 1L;

    public AdapterTimeoutException(String message) {
        super(message);
    }

    /**
     * Use this constructor whenever a non-null underlying cause exists (e.g.
     * {@code SocketTimeoutException}, {@code WebClientResponseException} for
     * a 408); use {@link #AdapterTimeoutException(String)} only for
     * adapter-internal failures with no underlying exception. Preserving the
     * cause chain is required for downstream debugging.
     */
    public AdapterTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public String reasonCode() {
        return "timeout";
    }
}
