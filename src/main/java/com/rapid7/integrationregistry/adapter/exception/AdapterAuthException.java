package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the upstream returns a 4xx authentication/authorization failure (typically
 * 401/403). Mapped by the future fan-out coordinator to
 * {@code unavailable_products[].reason = "auth_failure"} (RFC-001).
 */
public class AdapterAuthException extends Exception {

    public AdapterAuthException(String message) {
        super(message);
    }

    /**
     * Use this constructor whenever a non-null underlying cause exists (e.g.
     * {@code SocketTimeoutException}, {@code WebClientResponseException}); use
     * {@link #AdapterAuthException(String)} only for adapter-internal failures with no
     * underlying exception. Preserving the cause chain is required for downstream
     * debugging.
     */
    public AdapterAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
