package com.rapid7.integrationregistry.adapter.exception;

/**
 * Abstract parent for the registry's adapter exception family. Subclasses are
 * thrown by {@link com.rapid7.integrationregistry.adapter.IntegrationAdapter#fetch}
 * to signal per-product fetch failures. The fan-out coordinator catches this type
 * to dispatch on {@link #isTransient()} (stale-fallback eligibility) and
 * {@link #reasonCode()} (the {@code unavailable_products[].reason} value
 * surfaced on the read API per RFC-001).
 *
 * <p>Concrete subclasses live alongside this class in {@code adapter/exception/}.
 * The bundle exception family ({@code mapping/exception/}) deliberately has no
 * shared parent — see ADR-001.
 */
public abstract class AdapterException extends Exception {

    private static final long serialVersionUID = 1L;

    protected AdapterException(String message) {
        super(message);
    }

    /**
     * Use this constructor whenever a non-null underlying cause exists (e.g.
     * {@code WebClientResponseException}, {@code SocketTimeoutException}); use
     * {@link #AdapterException(String)} only for adapter-internal failures with
     * no underlying exception. Preserving the cause chain is required for
     * downstream debugging.
     */
    protected AdapterException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @return {@code true} when this failure is transient and qualifies for
     *         stale-tier fallback at the fan-out coordinator; {@code false}
     *         when permanent (no fallback). The classification is intrinsic to
     *         the failure mode, not policy at the catch site.
     */
    public abstract boolean isTransient();

    /**
     * @return the canonical {@code unavailable_products[].reason} value for
     *         this failure mode (RFC-001). One of:
     *         {@code "auth_failure"}, {@code "timeout"}, {@code "upstream_5xx"}.
     */
    public abstract String reasonCode();
}
