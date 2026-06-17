package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DataSourceResolutionTest {

  private static final VendorResolution IDENTITY =
      new VendorResolution(
          "microsoft-defender", "Microsoft Defender", VendorCategory.EDR, "microsoft", "Microsoft");

  @Test
  void constructor_shouldBuildRecord_whenBothFieldsProvided() {
    // Act
    DataSourceResolution resolution =
        new DataSourceResolution(IDENTITY, "Microsoft Defender for Endpoint");

    // Assert
    assertThat(resolution.identity()).isSameAs(IDENTITY);
    assertThat(resolution.displayName()).isEqualTo("Microsoft Defender for Endpoint");
  }

  @Test
  void constructor_shouldThrowNPE_whenIdentityNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new DataSourceResolution(null, "label"))
        .withMessage("identity");
  }

  @Test
  void constructor_shouldThrowNPE_whenDisplayNameNull() {
    // Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new DataSourceResolution(IDENTITY, null))
        .withMessage("displayName");
  }

  @Test
  void unknown_shouldCarryUnknownIdentityAndLabel_whenInvoked() {
    // Act
    DataSourceResolution unknown = DataSourceResolution.unknown();

    // Assert — identity is the VendorResolution singleton; label is the fixed "Unknown"
    assertThat(unknown.identity()).isSameAs(VendorResolution.unknown());
    assertThat(unknown.displayName()).isEqualTo("Unknown");
  }

  @Test
  void unknown_shouldReturnSameInstance_whenInvokedTwice() {
    // Act / Assert
    assertThat(DataSourceResolution.unknown()).isSameAs(DataSourceResolution.unknown());
  }
}
