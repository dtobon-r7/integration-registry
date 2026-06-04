package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class VendorCardSerializationTest {

  @Autowired private JacksonTester<VendorListEntryDto> listEntry;
  @Autowired private JacksonTester<VendorServiceCardDto> flat;
  @Autowired private JacksonTester<VendorServiceCardNestedDto> nested;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void vendorListEntry_shouldMatchSchema() throws Exception {
    var json = listEntry.write(new VendorListEntryDto("microsoft", "Microsoft", 2)).getJson();
    assertThat(json).contains("\"vendor_id\":\"microsoft\"");
    assertThat(json).contains("\"vendor_services_count\":2");
    assertThat(OpenApiSchemas.validate("VendorListEntry", mapper.readTree(json))).isEmpty();
  }

  @Test
  void flatCard_shouldIncludeVendorFields_andMatchSchema() throws Exception {
    var dto =
        new VendorServiceCardDto(
            "microsoft-defender",
            "Microsoft Defender",
            "microsoft",
            "Microsoft",
            "edr",
            4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"),
            HealthState.ERROR,
            Instant.parse("2026-04-22T14:30:00Z"));
    var json = flat.write(dto).getJson();
    assertThat(json).contains("\"vendor_id\":\"microsoft\"");
    assertThat(json).contains("\"vendor_name\":\"Microsoft\"");
    assertThat(json).contains("\"vendor_category\":\"edr\"");
    assertThat(OpenApiSchemas.validate("VendorServiceCard", mapper.readTree(json))).isEmpty();
  }

  @Test
  void nestedCard_shouldOmitVendorFields_andMatchSchema() throws Exception {
    var dto =
        new VendorServiceCardNestedDto(
            "microsoft-defender",
            "Microsoft Defender",
            "edr",
            4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"),
            HealthState.ERROR,
            Instant.parse("2026-04-22T14:30:00Z"));
    var json = nested.write(dto).getJson();
    assertThat(json).doesNotContain("vendor_id");
    assertThat(json).doesNotContain("vendor_name");
    assertThat(json).contains("\"vendor_service_id\":\"microsoft-defender\"");
    assertThat(OpenApiSchemas.validate("VendorServiceCardNested", mapper.readTree(json))).isEmpty();
  }

  @Test
  void flatCard_shouldRenderNullLastUpdatedAsExplicitNull() throws Exception {
    var dto =
        new VendorServiceCardDto(
            "jira",
            "Jira",
            "atlassian",
            "Atlassian",
            "itsm",
            0,
            List.of(),
            List.of(),
            HealthState.HEALTHY,
            null);
    var json = flat.write(dto).getJson();
    assertThat(json).contains("\"last_updated\":null");
  }
}
