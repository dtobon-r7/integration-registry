package com.rapid7.integrationregistry.mapping.loader;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration properties for the runtime bundle loader. Bound from the
 * {@code integration-registry.vendor-mapping.*} property tree.
 *
 * <p>Required fields ({@code bundleVersion}, {@code s3Bucket},
 * {@code s3KeyPrefix}) have no defaults — the deploy environment supplies
 * them via env vars / per-profile overrides. {@code cacheDir} defaults to
 * {@code ${java.io.tmpdir}/integration-registry/vendor-mapping}.
 *
 * <p>Activated via {@code @EnableConfigurationProperties(VendorMappingProperties.class)}
 * on the loader's {@code @Configuration} class.
 */
@ConfigurationProperties("integration-registry.vendor-mapping")
public record VendorMappingProperties(
    String bundleVersion,
    String s3Bucket,
    String s3KeyPrefix,
    Path cacheDir
) {

    private static final String FIELD_BUNDLE_VERSION = "bundleVersion";
    private static final String FIELD_S3_BUCKET = "s3Bucket";
    private static final String FIELD_S3_KEY_PREFIX = "s3KeyPrefix";

    public VendorMappingProperties {
        Objects.requireNonNull(bundleVersion, FIELD_BUNDLE_VERSION);
        Objects.requireNonNull(s3Bucket, FIELD_S3_BUCKET);
        Objects.requireNonNull(s3KeyPrefix, FIELD_S3_KEY_PREFIX);
        if (cacheDir == null) {
            cacheDir = Path.of(System.getProperty("java.io.tmpdir"),
                               "integration-registry", "vendor-mapping");
        }
    }

    /**
     * Composite S3 object key under the configured bucket — e.g.
     * {@code registry/mappings/vendor-mapping-v1.0.0.tgz}.
     */
    public String bundleObjectKey() {
        return s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz";
    }

    /**
     * Resolved disk-cache filename — e.g.
     * {@code <cacheDir>/vendor-mapping-v1.0.0.tgz}. Per-version filename
     * prevents cross-version cache reuse.
     */
    public Path cacheFilePath() {
        return cacheDir.resolve("vendor-mapping-" + bundleVersion + ".tgz");
    }
}
