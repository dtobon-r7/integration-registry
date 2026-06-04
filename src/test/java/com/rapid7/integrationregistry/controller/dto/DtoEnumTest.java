package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DtoEnumTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void healthState_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(HealthState.HEALTHY)).isEqualTo("\"healthy\"");
    assertThat(mapper.writeValueAsString(HealthState.WARNING)).isEqualTo("\"warning\"");
    assertThat(mapper.writeValueAsString(HealthState.ERROR)).isEqualTo("\"error\"");
    assertThat(mapper.writeValueAsString(HealthState.MISSING_DATA)).isEqualTo("\"missing_data\"");
    assertThat(mapper.writeValueAsString(HealthState.DISABLED)).isEqualTo("\"disabled\"");
  }

  @Test
  void errorCode_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(ErrorCode.UNAUTHENTICATED))
        .isEqualTo("\"UNAUTHENTICATED\"");
    assertThat(mapper.writeValueAsString(ErrorCode.NOT_FOUND)).isEqualTo("\"NOT_FOUND\"");
    assertThat(mapper.writeValueAsString(ErrorCode.INTERNAL)).isEqualTo("\"INTERNAL\"");
  }

  @Test
  void unavailableReason_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(UnavailableReason.TIMEOUT)).isEqualTo("\"timeout\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.UPSTREAM_5XX))
        .isEqualTo("\"upstream_5xx\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.AUTH_FAILURE))
        .isEqualTo("\"auth_failure\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.NO_DATA)).isEqualTo("\"no_data\"");
  }
}
