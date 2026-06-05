package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class UnavailableProductSerializationTest {

  @Autowired private JacksonTester<UnavailableProductDto> tester;

  @Test
  void staleSince_shouldBePresent_whenStaleTrue() throws Exception {
    var dto =
        new UnavailableProductDto(
            "InsightIDR", true, UnavailableReason.TIMEOUT, Instant.parse("2026-04-23T09:00:00Z"));
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":true");
    assertThat(json).contains("\"stale_since\":\"2026-04-23T09:00:00Z\"");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", json)).isEmpty();
  }

  @Test
  void staleSince_shouldBeAbsent_whenNull() throws Exception {
    var dto = new UnavailableProductDto("InsightIDR", false, UnavailableReason.UPSTREAM_5XX, null);
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":false");
    assertThat(json).doesNotContain("stale_since");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", json)).isEmpty();
  }

  @Test
  void shouldThrowIae_whenStaleTrueButStaleSinceNull() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new UnavailableProductDto("InsightIDR", true, UnavailableReason.TIMEOUT, null))
        .withMessageContaining(UnavailableProductDto.FIELD_STALE_SINCE);
  }

  @Test
  void shouldThrowIae_whenStaleFalseButStaleSincePresent() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new UnavailableProductDto(
                    "InsightIDR",
                    false,
                    UnavailableReason.NO_DATA,
                    Instant.parse("2026-04-23T09:00:00Z")))
        .withMessageContaining(UnavailableProductDto.FIELD_STALE_SINCE);
  }
}
