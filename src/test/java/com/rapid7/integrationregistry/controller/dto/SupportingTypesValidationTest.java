package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class SupportingTypesValidationTest {

  @Test
  void integrationTypeCount_shouldThrowNpe_whenTypeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IntegrationTypeCountDto(null, 1, 0))
        .withMessage(IntegrationTypeCountDto.FIELD_INTEGRATION_TYPE);
  }

  @Test
  void integrationTypeCount_shouldThrowIae_whenErrorCountExceedsTotal() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new IntegrationTypeCountDto("SIEM Event Source", 2, 3))
        .withMessageContaining(IntegrationTypeCountDto.FIELD_ERROR_COUNT);
  }

  @Test
  void responseMetadata_shouldThrowNpe_whenAsOfNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ResponseMetadataDto(true, null, "v1"))
        .withMessage(ResponseMetadataDto.FIELD_AS_OF);
  }

  @Test
  void errorBody_shouldThrowNpe_whenCodeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ErrorEnvelopeDto.ErrorBody(null, "msg"))
        .withMessage(ErrorEnvelopeDto.ErrorBody.FIELD_CODE);
  }
}
