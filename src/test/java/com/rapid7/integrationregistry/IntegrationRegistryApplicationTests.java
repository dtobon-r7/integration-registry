package com.rapid7.integrationregistry;

import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IntegrationRegistryApplicationTests {

    private static Path cacheDir;

    @BeforeAll
    static void seedCacheBeforeContextBootstrap() throws IOException {
        cacheDir = Files.createTempDirectory("integration-registry-context-test-");
        Path cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0.tgz");
        byte[] mvpSeedYaml;
        try (InputStream stream = IntegrationRegistryApplicationTests.class.getResourceAsStream(
                "/vendor-mapping/bundle/mvp-seed.yaml")) {
            assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
            mvpSeedYaml = stream.readAllBytes();
        }
        Files.write(cacheFile, BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml"));
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
        registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
        registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "test/mappings/");
        registry.add("integration-registry.vendor-mapping.cache-dir", () -> cacheDir.toString());
        // InsightConnect base-url/icon-base have no defaults (fail-fast at binding);
        // supply test values here, mirroring the vendor-mapping required props above.
        registry.add("integration-registry.insightconnect.base-url", () -> "http://icon.test.local");
        registry.add("integration-registry.insightconnect.icon-base", () -> "http://icon.test.local");
    }

    @MockitoBean
    S3Client s3Client;

    @Test
    void contextLoads() {
    }
}
