package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleLoadHealthIndicatorTest {

    private VendorMappingSnapshotHolder holder;
    private VendorMappingProperties properties;
    private BundleLoadHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        holder = new VendorMappingSnapshotHolder();
        properties = new VendorMappingProperties("v1.0.0", "test-bucket", "registry/mappings/", Path.of("/tmp"));
        indicator = new BundleLoadHealthIndicator(holder, properties);
    }

    private static VendorMappingSnapshot stubSnapshot(String version) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return VendorResolution.unknown();
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void health_shouldReturnDown_whenHolderEmpty() {
        // Arrange — holder is empty by default

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("bundle_version", "v1.0.0")
            .containsEntry("reason", "vendor mapping bundle not yet loaded");
        assertThat(health.getDetails()).doesNotContainKey("mapping_version");
    }

    @Test
    void health_shouldReturnUp_whenHolderLoaded() {
        // Arrange
        holder.set(stubSnapshot("v1.0.0"));

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("mapping_version", "v1.0.0")
            .containsEntry("bundle_version", "v1.0.0");
        assertThat(health.getDetails()).doesNotContainKey("reason");
    }

    @Test
    void health_shouldSurfaceLoadedMappingVersion_whenDifferentFromConfigured() {
        // Arrange — loaded version may differ from configured bundle_version
        // (e.g., the bundle's metadata.mapping_version is independent of the
        // pinned artifact version). Both should appear separately in details.
        holder.set(stubSnapshot("v2.5.0"));

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("mapping_version", "v2.5.0")
            .containsEntry("bundle_version", "v1.0.0");
    }
}
