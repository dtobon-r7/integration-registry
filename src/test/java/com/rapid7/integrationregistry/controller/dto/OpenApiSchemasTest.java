package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class OpenApiSchemasTest {

  @Test
  void validate_shouldReturnNoMessages_whenJsonMatchesSchema() {
    // IntegrationTypeCount is a small closed schema in the contract.
    String valid = "{\"integration_type\":\"SIEM Event Source\",\"total\":4,\"error_count\":1}";

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", valid)).isEmpty();
  }

  @Test
  void validate_shouldReturnMessages_whenRequiredFieldMissing() {
    String invalid = "{\"total\":4,\"error_count\":1}"; // missing integration_type

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", invalid)).isNotEmpty();
  }

  @Test
  void validate_shouldThrowIllegalArgument_whenSchemaNameUnknown() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> OpenApiSchemas.validate("NoSuchSchema", "{}"))
        .withMessageContaining("NoSuchSchema");
  }
}
