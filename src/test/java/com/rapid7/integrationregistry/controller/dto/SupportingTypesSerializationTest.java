package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class SupportingTypesSerializationTest {

  @Autowired private JacksonTester<IntegrationTypeCountDto> typeCount;
  @Autowired private JacksonTester<ResponseMetadataDto> metadata;
  @Autowired private JacksonTester<ErrorEnvelopeDto> error;

  @Test
  void integrationTypeCount_shouldMatchContractSchema() throws Exception {
    var json = typeCount.write(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)).getJson();
    assertThat(json).contains("\"integration_type\":\"SIEM Event Source\"");
    assertThat(json).contains("\"error_count\":1");
    assertThat(
            OpenApiSchemas.validate(
                "IntegrationTypeCount",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)))
        .isEmpty();
  }

  @Test
  void responseMetadata_shouldMatchContractSchema() throws Exception {
    var dto = new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
    var json = metadata.write(dto).getJson();
    assertThat(json).contains("\"cache_hit\":true");
    assertThat(json).contains("\"as_of\":\"2026-04-23T10:00:00Z\"");
    assertThat(json).contains("\"mapping_version\":\"v1.42.0\"");
    assertThat(
            OpenApiSchemas.validate(
                "ResponseMetadata",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)))
        .isEmpty();
  }

  @Test
  void errorEnvelope_shouldMatchContractSchema() throws Exception {
    var dto =
        new ErrorEnvelopeDto(
            new ErrorEnvelopeDto.ErrorBody(
                ErrorCode.NOT_FOUND, "Vendor service not found in this org"));
    var json = error.write(dto).getJson();
    assertThat(json).contains("\"error\":{");
    assertThat(json).contains("\"code\":\"NOT_FOUND\"");
    assertThat(
            OpenApiSchemas.validate(
                "ErrorEnvelope", new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)))
        .isEmpty();
  }
}
