package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BundleSchemaTest {

  private static JsonSchema schema;

  @BeforeAll
  static void loadSchema() throws IOException {
    schema = BundleSchemaResources.loadSchema();
  }

  private Set<ValidationMessage> validateFixture(String fixtureFileName) throws IOException {
    return schema.validate(BundleSchemaResources.readFixture(fixtureFileName));
  }

  // ---------- Positive cases ----------

  @Test
  void validate_shouldAccept_whenBundleIsMinimalValid() throws IOException {
    Set<ValidationMessage> errors = validateFixture("valid-minimal.json");
    assertThat(errors).isEmpty();
  }

  @Test
  void validate_shouldAccept_whenServicesArrayIsEmpty() throws IOException {
    Set<ValidationMessage> errors = validateFixture("valid-empty-services.json");
    assertThat(errors).isEmpty();
  }

  @Test
  void validate_shouldAccept_whenDataSourcesArrayIsEmpty() throws IOException {
    Set<ValidationMessage> errors = validateFixture("valid-empty-data-sources.json");
    assertThat(errors).isEmpty();
  }

  @Test
  void validate_shouldAccept_whenServicesKeyOmitted() throws IOException {
    Set<ValidationMessage> errors = validateFixture("valid-no-services-key.json");
    assertThat(errors).isEmpty();
  }

  @Test
  void validate_shouldAccept_whenSlugIsLiteralUnknown() throws IOException {
    // The schema permits "unknown" as a slug because it matches ^[a-z0-9_-]+$.
    // The bundle CI suite is the enforcement boundary for the reservation — not the schema.
    Set<ValidationMessage> errors = validateFixture("valid-unknown-slug.json");
    assertThat(errors).isEmpty();
  }

  @Test
  void validate_shouldAccept_whenMappingVersionHasPreReleaseSuffix() throws IOException {
    Set<ValidationMessage> errors = validateFixture("valid-mapping-version-prerelease.json");
    assertThat(errors).isEmpty();
  }

  // ---------- Negative cases ----------

  @Test
  void validate_shouldReject_whenApiVersionMissing() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-missing-api-version.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getMessage().contains("apiVersion"));
  }

  @Test
  void validate_shouldReject_whenApiVersionIsWrongValue() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-wrong-api-version.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".apiVersion"));
  }

  @Test
  void validate_shouldReject_whenKindIsWrongValue() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-wrong-kind.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".kind"));
  }

  @Test
  void validate_shouldReject_whenMappingVersionMissing() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-missing-mapping-version.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getMessage().contains("mapping_version"));
  }

  @Test
  void validate_shouldReject_whenMappingVersionIsNotSemver() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-mapping-version-bad-format.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getInstanceLocation().toString().contains(".metadata.mapping_version"));
  }

  @Test
  void validate_shouldReject_whenMappingVersionHasLeadingZeroPrereleaseSegment()
      throws IOException {
    Set<ValidationMessage> errors =
        validateFixture("invalid-mapping-version-leading-zero-prerelease.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getInstanceLocation().toString().contains(".metadata.mapping_version"));
  }

  @Test
  void validate_shouldReject_whenMappingVersionHasEmptyPrereleaseSegment() throws IOException {
    Set<ValidationMessage> errors =
        validateFixture("invalid-mapping-version-empty-prerelease-segment.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getInstanceLocation().toString().contains(".metadata.mapping_version"));
  }

  @Test
  void validate_shouldReject_whenVendorSlugFailsRegex() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-vendor-slug-uppercase.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getInstanceLocation().toString().contains(".spec.vendors[0].id"));
  }

  @Test
  void validate_shouldReject_whenServiceSlugFailsRegex() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-service-slug-uppercase.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors)
        .anyMatch(m -> m.getInstanceLocation().toString().contains(".services[0].id"));
  }

  @Test
  void validate_shouldReject_whenCategoryNotInEnum() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-unknown-category.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".category"));
  }

  @Test
  void validate_shouldReject_whenProductNotInEnum() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-unknown-product.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".product"));
  }

  @Test
  void validate_shouldReject_whenSourceTypeNotInEnum() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-unknown-source-type.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".source_type"));
  }

  @Test
  void validate_shouldReject_whenSourceValueContainsPipe() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-source-value-with-pipe.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".source_value"));
  }

  @Test
  void validate_shouldReject_whenUnknownPropertyOnVendor() throws IOException {
    // Proves additionalProperties: false is in effect — typos like `data_soruces`
    // or fabricated fields like `deprecated_at` fail validation.
    Set<ValidationMessage> errors = validateFixture("invalid-unknown-property.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getMessage().contains("deprecated_at"));
  }

  @Test
  void validate_shouldReject_whenSourceValueIsEmpty() throws IOException {
    Set<ValidationMessage> errors = validateFixture("invalid-source-value-empty.json");
    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(m -> m.getInstanceLocation().toString().contains(".source_value"));
  }
}
