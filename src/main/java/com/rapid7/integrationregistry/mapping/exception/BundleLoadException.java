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
 * <p>Payload-style exception per ADR-001: the structured payload is the
 * optional {@link #path()} (populated for cache I/O failures); the
 * underlying {@link Throwable} cause discriminates the remaining failure
 * modes via the named static factories.
 *
 * <p>The bundle exception family deliberately has no shared parent class
 * (ADR-001). The two siblings ({@link BundleParseException} and
 * {@link BundleLoadException}) have only one consumer (the boot-time
 * listener) which uses Java multi-catch. Adding a parent here would be
 * ceremony with no benefit — do not introduce one.
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
