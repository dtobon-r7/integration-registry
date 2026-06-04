package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenApiSchemasTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void validate_shouldReturnNoMessages_whenNodeMatchesSchema() throws Exception {
    // IntegrationTypeCount is a small closed schema in the contract.
    JsonNode valid =
        mapper.readTree(
            "{\"integration_type\":\"SIEM Event Source\",\"total\":4,\"error_count\":1}");

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", valid)).isEmpty();
  }

  @Test
  void validate_shouldReturnMessages_whenRequiredFieldMissing() throws Exception {
    JsonNode invalid =
        mapper.readTree("{\"total\":4,\"error_count\":1}"); // missing integration_type

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", invalid)).isNotEmpty();
  }

  @Test
  void validate_shouldThrowIllegalArgument_whenSchemaNameUnknown() throws Exception {
    JsonNode any = mapper.readTree("{}");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> OpenApiSchemas.validate("NoSuchSchema", any))
        .withMessageContaining("NoSuchSchema");
  }
}
