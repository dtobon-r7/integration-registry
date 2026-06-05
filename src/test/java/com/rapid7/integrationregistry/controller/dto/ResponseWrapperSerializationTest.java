package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class ResponseWrapperSerializationTest {

  @Autowired private JacksonTester<VendorServicesResponse> vendorServices;
  @Autowired private JacksonTester<VendorServiceDetailResponse> detail;
  @Autowired private JacksonTester<VendorsResponse> vendors;
  @Autowired private JacksonTester<VendorDetailResponse> vendorDetail;

  private ResponseMetadataDto meta() {
    return new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
  }

  private VendorServiceCardDto flatCard() {
    return new VendorServiceCardDto(
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
  }

  @Test
  void vendorServicesResponse_shouldMatchSchema() throws Exception {
    var dto = new VendorServicesResponse(List.of(flatCard()), List.of(), meta());
    var json = vendorServices.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[");
    assertThat(json).contains("\"unavailable_products\":[]");
    assertThat(json).contains("\"metadata\":{");
    assertThat(OpenApiSchemas.validate("VendorServicesResponse", json)).isEmpty();
  }

  @Test
  void vendorServiceDetailResponse_shouldMatchSchema() throws Exception {
    var ds =
        new DataSourceDto(
            "insightidr|product_type|microsoft-defender-endpoint",
            "Microsoft Defender for Endpoint",
            "SIEM Event Source",
            "InsightIDR",
            HealthState.HEALTHY,
            0,
            List.of());
    var dto =
        new VendorServiceDetailResponse(
            "microsoft-defender",
            "Microsoft Defender",
            "microsoft",
            "Microsoft",
            "edr",
            HealthState.ERROR,
            Instant.parse("2026-04-22T14:30:00Z"),
            List.of(ds),
            List.of(),
            meta());
    var json = detail.write(dto).getJson();
    assertThat(json).contains("\"data_sources\":[");
    assertThat(OpenApiSchemas.validate("VendorServiceDetailResponse", json)).isEmpty();
  }

  @Test
  void vendorsResponse_shouldMatchSchema() throws Exception {
    var dto =
        new VendorsResponse(
            List.of(new VendorListEntryDto("microsoft", "Microsoft", 2)), List.of(), meta());
    var json = vendors.write(dto).getJson();
    assertThat(json).contains("\"vendors\":[");
    assertThat(OpenApiSchemas.validate("VendorsResponse", json)).isEmpty();
  }

  @Test
  void vendorDetailResponse_shouldMatchSchema() throws Exception {
    var nested =
        new VendorServiceCardNestedDto(
            "microsoft-defender",
            "Microsoft Defender",
            "edr",
            4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"),
            HealthState.ERROR,
            Instant.parse("2026-04-22T14:30:00Z"));
    var dto =
        new VendorDetailResponse(
            "microsoft",
            "Microsoft",
            HealthState.ERROR,
            Instant.parse("2026-04-22T14:30:00Z"),
            List.of(nested),
            List.of(),
            meta());
    var json = vendorDetail.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[");
    // Nested card omits vendor_id/vendor_name: its first key is vendor_service_id, not vendor_id.
    assertThat(json).contains("\"vendor_services\":[{\"vendor_service_id\":");
    assertThat(json).doesNotContain("\"vendor_services\":[{\"vendor_id\":");
    assertThat(OpenApiSchemas.validate("VendorDetailResponse", json)).isEmpty();
  }

  @Test
  void allAdapterFailureShape_shouldBeEmptyPayloadWithPopulatedUnavailable() throws Exception {
    var dto =
        new VendorServicesResponse(
            List.of(),
            List.of(
                new UnavailableProductDto("InsightIDR", false, UnavailableReason.TIMEOUT, null)),
            meta());
    var json = vendorServices.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[]");
    assertThat(json).contains("\"unavailable_products\":[{");
    assertThat(OpenApiSchemas.validate("VendorServicesResponse", json)).isEmpty();
  }
}
