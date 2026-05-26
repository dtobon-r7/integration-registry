package com.rapid7.integrationregistry.adapter.exception;

/**
 * Thrown when the upstream returns a 5xx error or the adapter classifies a transport error
 * as 'upstream broken'. Mapped by the future fan-out coordinator to
 * {@code unavailable_products[].reason = "upstream_5xx"} (RFC-001).
 */
public class AdapterUpstreamException extends Exception {

    public AdapterUpstreamException(String message) {
        super(message);
    }

    /**
     * Use this constructor whenever a non-null underlying cause exists (e.g.
     * {@code SocketTimeoutException}, {@code WebClientResponseException}); use
     * {@link #AdapterUpstreamException(String)} only for adapter-internal failures with no
     * underlying exception. Preserving the cause chain is required for downstream
     * debugging.
     */
    public AdapterUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
