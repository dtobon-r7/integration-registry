package com.rapid7.integrationregistry.testsupport;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds a single-entry gzipped tarball ({@code .tgz}) for tests that stub
 * the S3 fetch with realistic bytes. Mirrors what T11's bundle-publish
 * pipeline produces: one tar entry named {@code vendor-mapping.yaml}
 * carrying the YAML bytes, gzipped.
 *
 * <p>Tests typically read {@code src/main/resources/vendor-mapping/bundle/mvp-seed.yaml}
 * via {@code getResourceAsStream}, then call {@link #tgzOf(byte[], String)}
 * with {@code "vendor-mapping.yaml"} as the entry name.
 */
public final class BundleArchiveBuilder {

    private BundleArchiveBuilder() {}

    public static byte[] tgzOf(byte[] yamlContent, String entryName) {
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(byteOut);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {

            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(yamlContent.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(yamlContent);
            tarOut.closeArchiveEntry();
            tarOut.finish();
            // Close streams in reverse order via try-with-resources; finish() flushes
            // tar bytes into gzip; gzip's close (via try-with-resources) finalizes
            // gzip frame into the byte buffer.
            tarOut.close();
            gzipOut.close();
            return byteOut.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build .tgz fixture", ex);
        }
    }
}
