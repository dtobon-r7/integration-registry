package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the adapter's own timeout fires (the upstream did not respond in time).
 * Mapped by the future fan-out coordinator to {@code unavailable_products[].reason = "timeout"}
 * (RFC-001).
 */
public class AdapterTimeoutException extends Exception {

    public AdapterTimeoutException(String message) {
        super(message);
    }

    /**
     * Use this constructor whenever a non-null underlying cause exists (e.g.
     * {@code SocketTimeoutException}, {@code WebClientResponseException}); use
     * {@link #AdapterTimeoutException(String)} only for adapter-internal failures with no
     * underlying exception. Preserving the cause chain is required for downstream
     * debugging.
     */
    public AdapterTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
