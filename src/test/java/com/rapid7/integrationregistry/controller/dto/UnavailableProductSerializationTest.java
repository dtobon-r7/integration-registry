package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class UnavailableProductSerializationTest {

  @Autowired private JacksonTester<UnavailableProductDto> tester;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void staleSince_shouldBePresent_whenStaleTrue() throws Exception {
    var dto =
        new UnavailableProductDto(
            "InsightIDR", true, UnavailableReason.TIMEOUT, Instant.parse("2026-04-23T09:00:00Z"));
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":true");
    assertThat(json).contains("\"stale_since\":\"2026-04-23T09:00:00Z\"");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", mapper.readTree(json))).isEmpty();
  }

  @Test
  void staleSince_shouldBeAbsent_whenNull() throws Exception {
    var dto = new UnavailableProductDto("InsightIDR", false, UnavailableReason.UPSTREAM_5XX, null);
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":false");
    assertThat(json).doesNotContain("stale_since");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", mapper.readTree(json))).isEmpty();
  }
}
