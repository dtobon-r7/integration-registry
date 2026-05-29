package com.rapid7.integrationregistry.mapping.loader;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the runtime bundle loader. Bound from the {@code
 * integration-registry.vendor-mapping.*} property tree.
 *
 * <p>Required fields ({@code bundleVersion}, {@code s3Bucket}, {@code s3KeyPrefix}) have no
 * defaults — the deploy environment supplies them via env vars / per-profile overrides. {@code
 * cacheDir} defaults to {@code ${java.io.tmpdir}/integration-registry/vendor-mapping}.
 *
 * <p>Activated via {@code @EnableConfigurationProperties(VendorMappingProperties.class)} on the
 * loader's {@code @Configuration} class.
 */
@ConfigurationProperties("integration-registry.vendor-mapping")
public record VendorMappingProperties(
    String bundleVersion, String s3Bucket, String s3KeyPrefix, Path cacheDir) {

  private static final String FIELD_BUNDLE_VERSION = "bundleVersion";
  private static final String FIELD_S3_BUCKET = "s3Bucket";
  private static final String FIELD_S3_KEY_PREFIX = "s3KeyPrefix";

  /**
   * {@code vMAJOR.MINOR.PATCH} — matches the bundle artifact-version naming convention. Rejecting
   * anything else at boot prevents path-traversal residue ({@code ..}) or whitespace from sneaking
   * into the cache filename or the S3 key.
   */
  private static final Pattern BUNDLE_VERSION_PATTERN = Pattern.compile("^v\\d+\\.\\d+\\.\\d+$");

  public VendorMappingProperties {
    Objects.requireNonNull(bundleVersion, FIELD_BUNDLE_VERSION);
    Objects.requireNonNull(s3Bucket, FIELD_S3_BUCKET);
    Objects.requireNonNull(s3KeyPrefix, FIELD_S3_KEY_PREFIX);
    if (!BUNDLE_VERSION_PATTERN.matcher(bundleVersion).matches()) {
      throw new IllegalArgumentException(
          "bundleVersion must match vMAJOR.MINOR.PATCH (e.g. v1.0.0); got: " + bundleVersion);
    }
    if (!s3KeyPrefix.endsWith("/")) {
      throw new IllegalArgumentException(
          "s3KeyPrefix must end with '/' (e.g. 'registry/mappings/'); got: " + s3KeyPrefix);
    }
    if (cacheDir == null) {
      cacheDir =
          Path.of(System.getProperty("java.io.tmpdir"), "integration-registry", "vendor-mapping");
    }
  }

  /**
   * Composite S3 object key under the configured bucket — e.g. {@code
   * registry/mappings/vendor-mapping-v1.0.0.tgz}.
   */
  public String bundleObjectKey() {
    return s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz";
  }

  /**
   * Resolved disk-cache filename — e.g. {@code <cacheDir>/vendor-mapping-v1.0.0.tgz}. Per-version
   * filename prevents cross-version cache reuse.
   */
  public Path cacheFilePath() {
    return cacheDir.resolve("vendor-mapping-" + bundleVersion + ".tgz");
  }
}
