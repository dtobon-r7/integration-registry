package com.rapid7.integrationregistry.mapping.exception;

import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BundleLoadExceptionTest {

    @Test
    void s3FetchFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IOException cause = new IOException("connection reset");

        // Act
        BundleLoadException ex = BundleLoadException.s3FetchFailed(cause);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle could not be fetched from S3")
            .contains("connection reset");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void cacheReadFailed_shouldCarryCauseAndPath_whenInvoked() {
        // Arrange
        Path cachePath = Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz");
        IOException cause = new IOException("permission denied");

        // Act
        BundleLoadException ex = BundleLoadException.cacheReadFailed(cachePath, cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping disk cache could not be read")
            .contains(cachePath.toString())
            .contains("permission denied");
        assertThat(ex.path()).contains(cachePath);
    }

    @Test
    void cacheWriteFailed_shouldCarryCauseAndPath_whenInvoked() {
        // Arrange
        Path cachePath = Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz");
        IOException cause = new IOException("disk full");

        // Act
        BundleLoadException ex = BundleLoadException.cacheWriteFailed(cachePath, cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping disk cache could not be written")
            .contains(cachePath.toString())
            .contains("disk full");
        assertThat(ex.path()).contains(cachePath);
    }

    @Test
    void archiveExtractFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IllegalStateException cause = new IllegalStateException("tarball is empty");

        // Act
        BundleLoadException ex = BundleLoadException.archiveExtractFailed(cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle archive could not be extracted")
            .contains("tarball is empty");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void parseFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IOException cause = new IOException("schema validation failed");

        // Act
        BundleLoadException ex = BundleLoadException.parseFailed(cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle could not be parsed")
            .contains("schema validation failed");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void path_shouldReturnOptional_alwaysNonNull() {
        // Arrange
        BundleLoadException withPath = BundleLoadException.cacheReadFailed(
            Path.of("/tmp/x"), new IOException("io"));
        BundleLoadException withoutPath = BundleLoadException.s3FetchFailed(new IOException("io"));

        // Act / Assert
        assertThat(withPath.path()).isInstanceOf(Optional.class).isPresent();
        assertThat(withoutPath.path()).isInstanceOf(Optional.class).isEmpty();
    }

    @Test
    void serialVersionUID_shouldBePresentAndPrivateStaticFinalLong_whenInspected() throws Exception {
        // Arrange
        Field field = BundleLoadException.class.getDeclaredField("serialVersionUID");

        // Act / Assert
        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(long.class);
    }

    @Test
    void classModifier_shouldNotBeFinal_perAdr001SharedInvariant() {
        // Arrange / Act
        int modifiers = BundleLoadException.class.getModifiers();

        // Assert — ADR-001 shared invariant: exception classes are not final
        // so future refactors can subclass without a breaking change.
        assertThat(Modifier.isFinal(modifiers)).isFalse();
    }

    @Test
    void independentlyCatchable_shouldNotBeAdapterExceptionOrSibling_whenThrown() {
        // ADR-001: family-independence (vs adapter) and sibling distinctness
        // (vs BundleParseException) are both required.
        BundleLoadException caught = BundleLoadException.s3FetchFailed(new IOException("test"));
        assertThat(caught)
            .isNotInstanceOf(AdapterException.class)
            .isNotInstanceOf(BundleParseException.class);
    }
}
