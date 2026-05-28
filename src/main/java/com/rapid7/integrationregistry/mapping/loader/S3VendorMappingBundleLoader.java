package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.input.BoundedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 *
 * <p>Two byte-budget caps defend against an outsized payload OOM-ing every
 * replica at boot: a 50 MB compressed cap on the S3 fetch and a 200 MB
 * inflated cap on the gzip stream (gzip-bomb defence). Both are hard rejects
 * surfaced as {@code archiveExtractFailed}.
 */
final class S3VendorMappingBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(S3VendorMappingBundleLoader.class);
    private static final String BUNDLE_ENTRY_NAME = "vendor-mapping.yaml";
    private static final String FIELD_S3_CLIENT = "s3Client";
    private static final String FIELD_PARSER = "parser";
    private static final String FIELD_PROPERTIES = "properties";
    private static final String TEMP_PREFIX = "vendor-mapping-";
    private static final String TEMP_SUFFIX = ".tgz.partial";

    /**
     * Hard cap on the compressed bundle byte count fetched from S3. The current
     * MVP-seed tarball is well under 10 KB; 50 MB is generous head-room for
     * future fixture growth while still bounding boot-time memory if S3 returns
     * an unexpectedly large payload.
     */
    private static final long MAX_BUNDLE_BYTES_COMPRESSED = 50L * 1024 * 1024;

    /**
     * Hard cap on the inflated tar bytes streamed through the gzip decoder.
     * Gzip-bomb defence: {@code BoundedInputStream} returns EOF at the cap rather
     * than throwing, so an oversized payload surfaces as an {@code IOException}
     * from the tar parser and is wrapped in {@code archiveExtractFailed}.
     */
    private static final long MAX_BUNDLE_BYTES_INFLATED = 200L * 1024 * 1024;

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
        try {
            byte[] cached = Files.readAllBytes(cacheFile);
            try {
                return parseFromBytes(cached);
            } catch (BundleLoadException corruptCache) {
                log.warn("vendor mapping disk cache corrupted at {}, deleting and falling through to S3 — {}",
                         cacheFile, corruptCache.getMessage());
                deleteCorruptCache(cacheFile);
                // fall through to S3 fetch
            }
        } catch (NoSuchFileException ignored) {
            // cache absent — fetch from S3
        } catch (IOException ioe) {
            throw BundleLoadException.cacheReadFailed(cacheFile, ioe);
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
            byte[] bytes = s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
            if (bytes.length > MAX_BUNDLE_BYTES_COMPRESSED) {
                throw BundleLoadException.archiveExtractFailed(
                    new IllegalStateException("Bundle exceeds compressed size cap: "
                        + bytes.length + " > " + MAX_BUNDLE_BYTES_COMPRESSED + " bytes"));
            }
            return bytes;
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
        // Inflate the gzip member into memory before any tar parsing. The
        // separate gzip→bytes step (rather than streaming through
        // TarArchiveInputStream) avoids the inflater-EOF race in commons-compress's
        // GzipCompressorInputStream, where a follow-up read after the gzip trailer
        // can NPE on an already-released Inflater.
        byte[] tarBytes = inflateBounded(tgzBytes);
        try (ByteArrayInputStream tarByteIn = new ByteArrayInputStream(tarBytes);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(tarByteIn)) {
            requireSingleNamedEntry(tarIn);
            VendorMappingSnapshot snapshot = parseSnapshot(tarIn);
            if (tarIn.getNextEntry() != null) {
                throw BundleLoadException.archiveExtractFailed(
                    new IllegalStateException("tarball has more than one entry; expected exactly "
                                              + BUNDLE_ENTRY_NAME));
            }
            return snapshot;
        } catch (IOException ex) {
            throw BundleLoadException.archiveExtractFailed(ex);
        }
    }

    private static byte[] inflateBounded(byte[] tgzBytes) throws BundleLoadException {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(tgzBytes);
             GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(byteIn);
             InputStream boundedIn = BoundedInputStream.builder()
                 .setInputStream(gzipIn)
                 .setMaxCount(MAX_BUNDLE_BYTES_INFLATED)
                 .get()) {
            return boundedIn.readAllBytes();
        } catch (IOException ex) {
            throw BundleLoadException.archiveExtractFailed(ex);
        }
    }

    private VendorMappingSnapshot parseSnapshot(TarArchiveInputStream tarIn) throws BundleLoadException {
        try {
            return parser.parse(tarIn);
        } catch (BundleParseException | IllegalStateException ex) {
            // BundleParseException = YAML/schema-validation failure.
            // IllegalStateException = schema/enum-sync drift (per BundleParser.parse Javadoc).
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
