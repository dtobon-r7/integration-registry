package com.rapid7.integrationregistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(properties = {
    "integration-registry.vendor-mapping.bundle-version=v1.0.0",
    "integration-registry.vendor-mapping.s3-bucket=test-bucket",
    "integration-registry.vendor-mapping.s3-key-prefix=test/mappings/"
})
class IntegrationRegistryApplicationTests {

    @MockitoBean
    S3Client s3Client;

    @Test
    void contextLoads() {
    }

}
