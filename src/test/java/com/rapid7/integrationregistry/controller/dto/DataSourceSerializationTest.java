package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class DataSourceSerializationTest {

  @Autowired private JacksonTester<IntegrationDto> integration;
  @Autowired private JacksonTester<DataSourceDto> dataSource;
  private final ObjectMapper mapper = new ObjectMapper();

  private IntegrationDto healthyInstance() {
    return new IntegrationDto(
        "es_1234",
        "DC1-Defender",
        HealthState.HEALTHY,
        Instant.parse("2026-04-23T09:00:00Z"),
        "https://idr.example/eventsources/es_1234");
  }

  @Test
  void integration_shouldOmitInternalFields_andMatchSchema() throws Exception {
    var json = integration.write(healthyInstance()).getJson();
    assertThat(json).contains("\"integration_id\":\"es_1234\"");
    assertThat(json).doesNotContain("data_source_id");
    assertThat(json).doesNotContain("source_type");
    assertThat(json).doesNotContain("source_value");
    assertThat(json).doesNotContain("customer_account_id");
    assertThat(OpenApiSchemas.validate("Integration", mapper.readTree(json))).isEmpty();
  }

  @Test
  void integration_shouldRenderNullableFieldsAsExplicitNull() throws Exception {
    var dto =
        new IntegrationDto(
            "c_456",
            null,
            HealthState.HEALTHY,
            null,
            "https://automation.example/connections/c_456");
    var json = integration.write(dto).getJson();
    assertThat(json).contains("\"integration_label\":null");
    assertThat(json).contains("\"last_success_timestamp\":null");
    assertThat(OpenApiSchemas.validate("Integration", mapper.readTree(json))).isEmpty();
  }

  @Test
  void dataSource_shouldNestIntegrations_andMatchSchema() throws Exception {
    var dto =
        new DataSourceDto(
            "insightidr|product_type|microsoft-defender-endpoint",
            "Microsoft Defender for Endpoint",
            "SIEM Event Source",
            "InsightIDR",
            HealthState.HEALTHY,
            1,
            List.of(healthyInstance()));
    var json = dataSource.write(dto).getJson();
    assertThat(json)
        .contains("\"data_source_id\":\"insightidr|product_type|microsoft-defender-endpoint\"");
    assertThat(json).contains("\"integrations_count\":1");
    assertThat(json).contains("\"integrations\":[");
    assertThat(OpenApiSchemas.validate("DataSource", mapper.readTree(json))).isEmpty();
  }

  @Test
  void dataSource_shouldThrowIae_whenCountDoesNotMatchListSize() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DataSourceDto(
                    "id",
                    "name",
                    "SIEM Event Source",
                    "InsightIDR",
                    HealthState.HEALTHY,
                    5,
                    List.of(healthyInstance())))
        .withMessageContaining(DataSourceDto.FIELD_INTEGRATIONS_COUNT);
  }
}
