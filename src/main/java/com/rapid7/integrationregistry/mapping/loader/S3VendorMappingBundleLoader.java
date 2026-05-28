package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Cache-first runtime loader that produces a {@link VendorMappingSnapshot} at
 * boot. The cache is a same-version-restart optimization (per RFC-001
 * §Bundle lifecycle): cache hit reads the local copy; cache corruption
 * (bad gzip / wrong tar entry / parse failure) deletes the file and falls
 * through to S3; cache-read I/O errors propagate as {@link BundleLoadException}.
 *
 * <p>Cache writes are atomic: bytes are written to a sibling temp file and
 * then renamed via {@link Files#move(Path, Path, java.nio.file.CopyOption...)}
 * with {@code ATOMIC_MOVE}, so a crash mid-write leaves either the previous
 * cache or no cache, never a partial file.
 *
 * <p>The bundle artifact is a {@code .tgz} (gzipped tarball) with a single
 * entry named {@code vendor-mapping.yaml}; the YAML is then handed to the
 * parser. Multi-entry tarballs and non-standard entry names are rejected.
 */
final class S3VendorMappingBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(S3VendorMappingBundleLoader.class);
    private static final String BUNDLE_ENTRY_NAME = "vendor-mapping.yaml";
    private static final String FIELD_S3_CLIENT = "s3Client";
    private static final String FIELD_PARSER = "parser";
    private static final String FIELD_PROPERTIES = "properties";
    private static final String TEMP_PREFIX = "vendor-mapping-";
    private static final String TEMP_SUFFIX = ".tgz.partial";

    private final S3Client s3Client;
    private final BundleParser parser;
    private final VendorMappingProperties properties;

    S3VendorMappingBundleLoader(S3Client s3Client, BundleParser parser, VendorMappingProperties properties) {
        this.s3Client = Objects.requireNonNull(s3Client, FIELD_S3_CLIENT);
        this.parser = Objects.requireNonNull(parser, FIELD_PARSER);
        this.properties = Objects.requireNonNull(properties, FIELD_PROPERTIES);
    }

    VendorMappingSnapshot load() throws BundleLoadException {
        Path cacheFile = properties.cacheFilePath();
        if (Files.exists(cacheFile)) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(cacheFile);
            } catch (IOException ioe) {
                throw BundleLoadException.cacheReadFailed(cacheFile, ioe);
            }
            try {
                return parseFromBytes(bytes);
            } catch (BundleLoadException corruptCache) {
                log.warn("vendor mapping disk cache corrupted at {}, deleting and falling through to S3 — {}",
                         cacheFile, corruptCache.getMessage());
                deleteCorruptCache(cacheFile);
                // fall through to S3 fetch
            }
        }
        byte[] bytes = fetchFromS3();
        writeCacheAtomically(cacheFile, bytes);
        return parseFromBytes(bytes);
    }

    private byte[] fetchFromS3() throws BundleLoadException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.s3Bucket())
                .key(properties.bundleObjectKey())
                .build();
            return s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
        } catch (SdkException ex) {
            throw BundleLoadException.s3FetchFailed(ex);
        }
    }

    private void writeCacheAtomically(Path cacheFile, byte[] bytes) throws BundleLoadException {
        try {
            Files.createDirectories(cacheFile.getParent());
            Path temp = Files.createTempFile(cacheFile.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
            Files.write(temp, bytes);
            Files.move(temp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw BundleLoadException.cacheWriteFailed(cacheFile, ex);
        }
    }

    private VendorMappingSnapshot parseFromBytes(byte[] tgzBytes) throws BundleLoadException {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(tgzBytes);
             GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(byteIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            requireSingleNamedEntry(tarIn);
            return parseSnapshot(tarIn);
        } catch (IOException ex) {
            throw BundleLoadException.archiveExtractFailed(ex);
        }
    }

    private VendorMappingSnapshot parseSnapshot(TarArchiveInputStream tarIn) throws BundleLoadException {
        try {
            return parser.parse(tarIn);
        } catch (BundleParseException | IllegalStateException ex) {
            // BundleParseException = bundle-data failure (YAML / schema-validation).
            // IllegalStateException = schema/enum-sync drift or missing schema resource
            // (per BundleParser.parse Javadoc). Both are parse-class failures.
            throw BundleLoadException.parseFailed(ex);
        }
    }

    private static void requireSingleNamedEntry(TarArchiveInputStream tarIn) throws BundleLoadException, IOException {
        TarArchiveEntry entry = tarIn.getNextEntry();
        if (entry == null) {
            throw BundleLoadException.archiveExtractFailed(
                new IllegalStateException("tarball is empty"));
        }
        if (!BUNDLE_ENTRY_NAME.equals(entry.getName())) {
            throw BundleLoadException.archiveExtractFailed(
                new IllegalStateException("expected entry " + BUNDLE_ENTRY_NAME
                                          + " but found " + entry.getName()));
        }
    }

    private void deleteCorruptCache(Path cacheFile) {
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException ioe) {
            log.warn("failed to delete corrupt cache file at {}; will overwrite via atomic move", cacheFile, ioe);
        }
    }
}
