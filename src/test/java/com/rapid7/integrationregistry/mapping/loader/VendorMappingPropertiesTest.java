package com.rapid7.integrationregistry.mapping.loader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorMappingPropertiesTest {

    private static VendorMappingProperties fixture(Path cacheDir) {
        return new VendorMappingProperties(
            "v1.0.0",
            "test-bucket",
            "registry/mappings/",
            cacheDir);
    }

    @Test
    void bundleObjectKey_shouldComposeKey_whenAllFieldsSet() {
        // Arrange
        VendorMappingProperties props = fixture(Path.of("/tmp"));

        // Act
        String key = props.bundleObjectKey();

        // Assert
        assertThat(key).isEqualTo("registry/mappings/vendor-mapping-v1.0.0.tgz");
    }

    @Test
    void cacheFilePath_shouldComposePath_whenAllFieldsSet() {
        // Arrange
        VendorMappingProperties props = fixture(Path.of("/tmp/integration-registry/vendor-mapping"));

        // Act
        Path cacheFile = props.cacheFilePath();

        // Assert
        assertThat(cacheFile).isEqualTo(
            Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz"));
    }

    @Test
    void properties_shouldDefaultCacheDir_whenNullPassed() {
        // Arrange
        Path expected = Path.of(System.getProperty("java.io.tmpdir"),
                                "integration-registry", "vendor-mapping");

        // Act
        VendorMappingProperties props = fixture(null);

        // Assert
        assertThat(props.cacheDir()).isEqualTo(expected);
    }

    @Test
    void properties_shouldThrowNpe_whenBundleVersionNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties(null, "b", "p/", Path.of("/tmp")))
            .withMessage("bundleVersion");
    }

    @Test
    void properties_shouldThrowNpe_whenS3BucketNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties("v", null, "p/", Path.of("/tmp")))
            .withMessage("s3Bucket");
    }

    @Test
    void properties_shouldThrowNpe_whenS3KeyPrefixNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties("v", "b", null, Path.of("/tmp")))
            .withMessage("s3KeyPrefix");
    }
}
