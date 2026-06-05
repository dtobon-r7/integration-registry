package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

/**
 * Verifies each wire enum serializes to its contract token through the SAME Spring-configured
 * Jackson 3 ({@code tools.jackson}) mapper that serves real responses — not a standalone Jackson 2
 * {@code ObjectMapper}. Serializing through the production mapper is what makes these assertions
 * load-bearing: a Jackson-3-specific {@code @JsonValue} regression on any token would fail here.
 */
@JsonTest
class DtoEnumTest {

  @Autowired private JacksonTester<HealthState> healthState;
  @Autowired private JacksonTester<ErrorCode> errorCode;
  @Autowired private JacksonTester<UnavailableReason> unavailableReason;

  @Test
  void healthState_shouldSerializeToWireTokens() throws Exception {
    assertThat(healthState.write(HealthState.HEALTHY).getJson()).isEqualTo("\"healthy\"");
    assertThat(healthState.write(HealthState.WARNING).getJson()).isEqualTo("\"warning\"");
    assertThat(healthState.write(HealthState.ERROR).getJson()).isEqualTo("\"error\"");
    assertThat(healthState.write(HealthState.MISSING_DATA).getJson()).isEqualTo("\"missing_data\"");
    assertThat(healthState.write(HealthState.DISABLED).getJson()).isEqualTo("\"disabled\"");
  }

  @Test
  void errorCode_shouldSerializeToWireTokens() throws Exception {
    assertThat(errorCode.write(ErrorCode.UNAUTHENTICATED).getJson())
        .isEqualTo("\"UNAUTHENTICATED\"");
    assertThat(errorCode.write(ErrorCode.NOT_FOUND).getJson()).isEqualTo("\"NOT_FOUND\"");
    assertThat(errorCode.write(ErrorCode.INTERNAL).getJson()).isEqualTo("\"INTERNAL\"");
  }

  @Test
  void unavailableReason_shouldSerializeToWireTokens() throws Exception {
    assertThat(unavailableReason.write(UnavailableReason.TIMEOUT).getJson())
        .isEqualTo("\"timeout\"");
    assertThat(unavailableReason.write(UnavailableReason.UPSTREAM_5XX).getJson())
        .isEqualTo("\"upstream_5xx\"");
    assertThat(unavailableReason.write(UnavailableReason.AUTH_FAILURE).getJson())
        .isEqualTo("\"auth_failure\"");
    assertThat(unavailableReason.write(UnavailableReason.NO_DATA).getJson())
        .isEqualTo("\"no_data\"");
  }
}
