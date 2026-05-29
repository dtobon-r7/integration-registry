package com.rapid7.integrationregistry.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class SourceIdentifierTest {

  @Test
  void constructor_shouldBuildRecord_whenBothFieldsProvided() {
    // Arrange
    String sourceType = "plugin_name";
    String sourceValue = "jira";

    // Act
    SourceIdentifier identifier = new SourceIdentifier(sourceType, sourceValue);

    // Assert
    assertThat(identifier.sourceType()).isEqualTo(sourceType);
    assertThat(identifier.sourceValue()).isEqualTo(sourceValue);
  }

  @Test
  void constructor_shouldThrowNPE_whenSourceTypeNull() {
    // Arrange
    String sourceValue = "jira";

    // Act + Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new SourceIdentifier(null, sourceValue))
        .withMessage(SourceIdentifier.FIELD_SOURCE_TYPE);
  }

  @Test
  void constructor_shouldThrowNPE_whenSourceValueNull() {
    // Arrange
    String sourceType = "plugin_name";

    // Act + Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new SourceIdentifier(sourceType, null))
        .withMessage(SourceIdentifier.FIELD_SOURCE_VALUE);
  }
}
