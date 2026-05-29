package com.rapid7.integrationregistry.mapping.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import com.rapid7.integrationregistry.testsupport.S3TestFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

class S3VendorMappingBundleLoaderTest {

  private static final String BUCKET = "test-bucket";
  private static final String KEY_PREFIX = "registry/mappings/";
  private static final String VERSION = "v1.0.0";
  private static final String EXPECTED_KEY = "registry/mappings/vendor-mapping-v1.0.0.tgz";
  private static final String ENTRY_NAME = "vendor-mapping.yaml";

  @TempDir Path tempDir;

  private S3Client s3Client;
  private BundleParser parser;
  private VendorMappingProperties properties;
  private S3VendorMappingBundleLoader loader;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    parser = new BundleParser();
    properties = new VendorMappingProperties(VERSION, BUCKET, KEY_PREFIX, tempDir);
    loader = new S3VendorMappingBundleLoader(s3Client, parser, properties);
  }

  private static byte[] readMvpSeed() throws IOException {
    try (InputStream stream =
        S3VendorMappingBundleLoaderTest.class.getResourceAsStream(
            "/vendor-mapping/bundle/mvp-seed.yaml")) {
      assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
      return stream.readAllBytes();
    }
  }

  @Test
  void load_shouldReadFromDisk_whenCacheExists() throws Exception {
    // Arrange
    byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
    Path cacheFile = properties.cacheFilePath();
    Files.createDirectories(cacheFile.getParent());
    Files.write(cacheFile, tgz);

    // Act
    VendorMappingSnapshot snapshot = loader.load();

    // Assert
    assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
    assertThat(
            snapshot
                .lookup(
                    ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint")
                .vendorServiceId())
        .isEqualTo("microsoft-defender");
    verify(s3Client, never())
        .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
  }

  @Test
  void load_shouldFetchS3_whenCacheMissing() throws Exception {
    // Arrange
    byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(tgz));

    // Act
    VendorMappingSnapshot snapshot = loader.load();

    // Assert
    assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
    Path cacheFile = properties.cacheFilePath();
    assertThat(cacheFile).exists();
    assertThat(Files.readAllBytes(cacheFile)).isEqualTo(tgz);
  }

  @Test
  void load_shouldFallthroughToS3_whenCacheCorrupted() throws Exception {
    // Arrange — write garbage bytes to the cache (not a valid gzip).
    Path cacheFile = properties.cacheFilePath();
    Files.createDirectories(cacheFile.getParent());
    Files.write(cacheFile, "this is not a tarball".getBytes(StandardCharsets.UTF_8));

    byte[] freshTgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(freshTgz));

    // Act
    VendorMappingSnapshot snapshot = loader.load();

    // Assert
    assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
    verify(s3Client).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    // Cache was replaced with the fresh fetch.
    assertThat(Files.readAllBytes(cacheFile)).isEqualTo(freshTgz);
  }

  @Test
  void load_shouldThrowS3FetchFailed_whenS3Throws() {
    // Arrange
    SdkClientException sdkEx = SdkClientException.create("connection reset");
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(sdkEx);

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("could not be fetched from S3");
    assertThat(thrown.getCause()).isSameAs(sdkEx);
    assertThat(thrown.path()).isEmpty();
  }

  @Test
  void load_shouldThrowArchiveExtractFailed_whenTarballEmpty() {
    // Arrange — gzip a tar with zero entries.
    byte[] handBuiltEmptyTgz = handBuildEmptyTgz();
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(handBuiltEmptyTgz));

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("archive could not be extracted");
    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getCause().getMessage()).contains("empty");
  }

  private static byte[] handBuildEmptyTgz() {
    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(byteOut);
        TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
      tarOut.finish();
      tarOut.close();
      gzipOut.close();
      return byteOut.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @Test
  void load_shouldThrowArchiveExtractFailed_whenEntryNameWrong() throws Exception {
    // Arrange — tarball with a single entry named "wrong-name.yaml"
    byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), "wrong-name.yaml");
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(tgz));

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("archive could not be extracted");
    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getCause().getMessage())
        .contains("expected entry vendor-mapping.yaml")
        .contains("wrong-name.yaml");
  }

  @Test
  void load_shouldThrowParseFailed_whenYamlInvalid() throws Exception {
    // Arrange — tarball whose yaml content is structurally invalid against the schema
    // (source_value contains the reserved '|' character).
    String invalidYaml =
        """
            apiVersion: registry.rapid7.com/v1
            kind: VendorMapping
            metadata:
              mapping_version: v1.0.0
            spec:
              vendors:
                - id: microsoft
                  name: Microsoft
                  services:
                    - id: microsoft-defender
                      name: Microsoft Defender
                      category: edr
                      data_sources:
                        - product: InsightIDR
                          source_type: product_type
                          source_value: "has|pipe"
                          display_name: Bad
            """;
    byte[] tgz =
        BundleArchiveBuilder.tgzOf(invalidYaml.getBytes(StandardCharsets.UTF_8), ENTRY_NAME);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(tgz));

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("could not be parsed");
    assertThat(thrown.getCause())
        .isInstanceOf(com.rapid7.integrationregistry.mapping.exception.BundleParseException.class);
  }

  @Test
  void load_shouldThrowCacheReadFailed_whenIoErrorOnRead() throws Exception {
    // Arrange — make the cache file a directory (so Files.readAllBytes throws IOException
    // on read; this is platform-portable: a directory is not a regular file).
    Path cacheFile = properties.cacheFilePath();
    Files.createDirectories(cacheFile); // create as a directory, not a file

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("disk cache could not be read");
    assertThat(thrown.path()).contains(cacheFile);
    verify(s3Client, never())
        .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
  }

  @Test
  void load_shouldThrowArchiveExtractFailed_whenBundleExceedsCompressedSizeCap() {
    // Arrange — 50 MB + 1 byte synthetic payload; content does not need to
    // be a valid tgz because the size check fires before any unzip work.
    byte[] oversized = new byte[(int) (50L * 1024 * 1024) + 1];
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(oversized));

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("archive could not be extracted");
    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getCause().getMessage()).contains("compressed size cap");
  }

  @Test
  void load_shouldThrowArchiveExtractFailed_whenTarballHasMultipleEntries() throws Exception {
    // Arrange — first entry is the canonical "vendor-mapping.yaml" with valid content,
    // but a second entry follows; reject multi-entry tarballs even when entry #1
    // would parse successfully.
    byte[] mvpBytes = readMvpSeed();
    byte[] tgz = buildMultiEntryTgz(mvpBytes);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(tgz));

    // Act / Assert
    BundleLoadException thrown =
        assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
    assertThat(thrown.getMessage()).contains("archive could not be extracted");
    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getCause().getMessage()).contains("more than one entry");
  }

  private static byte[] buildMultiEntryTgz(byte[] firstEntryContent) {
    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(byteOut);
        TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
      TarArchiveEntry first = new TarArchiveEntry(ENTRY_NAME);
      first.setSize(firstEntryContent.length);
      tarOut.putArchiveEntry(first);
      tarOut.write(firstEntryContent);
      tarOut.closeArchiveEntry();

      byte[] extraContent = "extra".getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry second = new TarArchiveEntry("extra-file.txt");
      second.setSize(extraContent.length);
      tarOut.putArchiveEntry(second);
      tarOut.write(extraContent);
      tarOut.closeArchiveEntry();

      tarOut.finish();
      tarOut.close();
      gzipOut.close();
      return byteOut.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @Test
  void load_shouldUseExpectedBucketAndKey_whenFetchingS3() throws Exception {
    // Arrange
    byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(S3TestFixtures.responseBytesOf(tgz));
    ArgumentCaptor<GetObjectRequest> reqCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);

    // Act
    loader.load();

    // Assert
    verify(s3Client).getObject(reqCaptor.capture(), any(ResponseTransformer.class));
    GetObjectRequest req = reqCaptor.getValue();
    assertThat(req.bucket()).isEqualTo(BUCKET);
    assertThat(req.key()).isEqualTo(EXPECTED_KEY);
  }
}
