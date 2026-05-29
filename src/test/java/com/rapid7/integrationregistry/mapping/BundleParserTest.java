package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.core.JacksonException;
import com.networknt.schema.ValidationMessage;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BundleParserTest {

  private static final String FIXTURES_ROOT = "/vendor-mapping/bundle/";

  private static InputStream openFixture(String fixtureFileName) {
    InputStream stream =
        BundleParserTest.class.getResourceAsStream(FIXTURES_ROOT + fixtureFileName);
    assertThat(stream)
        .as("fixture resource %s present on classpath", FIXTURES_ROOT + fixtureFileName)
        .isNotNull();
    return stream;
  }

  @Test
  void parse_shouldReturnSnapshot_whenValidMinimalBundle() throws Exception {
    // Arrange
    BundleParser parser = new BundleParser();

    // Act
    VendorMappingSnapshot snapshot;
    try (InputStream stream = openFixture("valid-minimal.yaml")) {
      snapshot = parser.parse(stream);
    }

    // Assert
    assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
    VendorResolution resolution =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
    assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
    assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
    assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
    assertThat(resolution.vendorId()).isEqualTo("microsoft");
    assertThat(resolution.vendorName()).isEqualTo("Microsoft");
  }

  @Test
  void parse_shouldThrowBundleParseException_whenYamlSyntaxIsInvalid() throws IOException {
    // Arrange
    BundleParser parser = new BundleParser();

    // Act / Assert
    try (InputStream stream = openFixture("invalid-yaml-syntax.yaml")) {
      BundleParseException ex =
          assertThatExceptionOfType(BundleParseException.class)
              .isThrownBy(() -> parser.parse(stream))
              .actual();
      assertThat(ex.validationMessages()).isEmpty();
      assertThat(ex.getCause()).isInstanceOf(JacksonException.class);
    }
  }

  @Test
  void parse_shouldThrowBundleParseException_whenSchemaValidationFails() throws IOException {
    // Arrange
    BundleParser parser = new BundleParser();

    // Act / Assert
    try (InputStream stream = openFixture("invalid-schema.yaml")) {
      BundleParseException ex =
          assertThatExceptionOfType(BundleParseException.class)
              .isThrownBy(() -> parser.parse(stream))
              .actual();
      assertThat(ex.getCause()).isNull();
      Set<ValidationMessage> messages = ex.validationMessages();
      assertThat(messages).isNotEmpty();
      assertThat(messages)
          .anyMatch(m -> m.getInstanceLocation().toString().contains("source_value"));
      assertThat(ex.getMessage())
          .as("schemaInvalid synthesizes a summary from the validation messages")
          .contains("source_value");
    }
  }

  @Test
  void parse_shouldThrowNullPointerException_whenStreamIsNull() {
    // Arrange
    BundleParser parser = new BundleParser();

    // Act / Assert
    assertThatNullPointerException().isThrownBy(() -> parser.parse(null)).withMessage("yamlStream");
  }

  @Test
  void parse_shouldReturnSnapshotWithUnknownLookup_whenTripletAbsent() throws Exception {
    // Arrange
    BundleParser parser = new BundleParser();

    // Act
    VendorMappingSnapshot snapshot;
    try (InputStream stream = openFixture("valid-minimal.yaml")) {
      snapshot = parser.parse(stream);
    }
    VendorResolution result =
        snapshot.lookup(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "not-in-the-bundle");

    // Assert
    assertThat(result).isSameAs(VendorResolution.unknown());
  }

  @Test
  void parse_shouldReturnSnapshot_whenStreamProvidedAsByteArray() throws Exception {
    // Arrange — proves the stream type contract is satisfied with any InputStream,
    // not just classpath InputStreams.
    BundleParser parser = new BundleParser();
    byte[] yaml =
        """
            apiVersion: registry.rapid7.com/v1
            kind: VendorMapping
            metadata:
              mapping_version: v9.9.9
            spec:
              vendors: []
            """
            .getBytes(StandardCharsets.UTF_8);

    // Act
    VendorMappingSnapshot snapshot = parser.parse(new ByteArrayInputStream(yaml));

    // Assert
    assertThat(snapshot.mappingVersion()).isEqualTo("v9.9.9");
    assertThat(snapshot.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "anything"))
        .isSameAs(VendorResolution.unknown());
  }
}
