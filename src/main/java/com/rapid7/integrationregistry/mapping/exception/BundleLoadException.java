package com.rapid7.integrationregistry.mapping.exception;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Thrown by the runtime bundle loader when the vendor-mapping bundle cannot be
 * retrieved, decoded, or parsed at boot. Caught by the boot-time listener,
 * which maps it to a readiness-probe-down state plus a structured ERROR log
 * entry built from {@link #getMessage()}, the cause, and {@link #path()} when
 * present.
 *
 * <p>This is the <em>payload-style</em> exception per ADR-001: the structured
 * payload here is the optional {@link #path()} (populated for cache I/O
 * failures), and the underlying {@link Throwable} cause discriminates the
 * remaining failure modes via the named static factories. Contrast with the
 * <em>marker-style</em> adapter exceptions in
 * {@code com.rapid7.integrationregistry.adapter.exception} which carry only
 * message and cause. See ADR-001 for the convention.
 */
public class BundleLoadException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Optional<Path> path;

    private BundleLoadException(String message, Throwable cause, Path path) {
        super(message, cause);
        this.path = Optional.ofNullable(path);
    }

    public static BundleLoadException s3FetchFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle could not be fetched from S3: " + cause.getMessage(),
            cause, null);
    }

    public static BundleLoadException cacheReadFailed(Path path, Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping disk cache could not be read at " + path + ": " + cause.getMessage(),
            cause, path);
    }

    public static BundleLoadException cacheWriteFailed(Path path, Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping disk cache could not be written at " + path + ": " + cause.getMessage(),
            cause, path);
    }

    public static BundleLoadException archiveExtractFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle archive could not be extracted: " + cause.getMessage(),
            cause, null);
    }

    public static BundleLoadException parseFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle could not be parsed: " + cause.getMessage(),
            cause, null);
    }

    public Optional<Path> path() {
        return path;
    }
}
