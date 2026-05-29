package com.rapid7.integrationregistry.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.rapid7.integrationregistry.mapping.SourceType;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DataSourceIdMinterStringOverloadTest {

  @Test
  void mintString_shouldProduceCanonicalId_forInsightIdrProductTypeVector() {
    // Act
    String result =
        DataSourceIdMinter.mint("InsightIDR", "product_type", "microsoft-defender-endpoint");

    // Assert
    assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
  }

  @Test
  void mintString_shouldProduceCanonicalId_forInsightConnectPluginNameVector() {
    // Act
    String result = DataSourceIdMinter.mint("InsightConnect", "plugin_name", "microsoft-defender");

    // Assert
    assertThat(result).isEqualTo("insightconnect|plugin_name|microsoft-defender");
  }

  @Test
  void mintString_shouldProduceCanonicalId_forSurfaceCommandIntegrationIdVector() {
    // Act
    String result =
        DataSourceIdMinter.mint(
            "Surface Command", "integration_id", "com.rapid7.microsoft-defender-for-endpoint");

    // Assert
    assertThat(result)
        .isEqualTo("surface-command|integration_id|com.rapid7.microsoft-defender-for-endpoint");
  }

  @Test
  void mintString_shouldProduceSameResultAsEnumOverload_forEverySourceType() {
    // Arrange — parity guard: the enum overload must delegate to the String one
    // and produce identical output.
    for (SourceType sourceType : SourceType.values()) {
      // Act
      String viaEnum = DataSourceIdMinter.mint("InsightIDR", sourceType, "value-x");
      String viaString = DataSourceIdMinter.mint("InsightIDR", sourceType.wireForm(), "value-x");

      // Assert
      assertThat(viaString)
          .as("mint via String must match mint via SourceType enum for %s", sourceType)
          .isEqualTo(viaEnum);
    }
  }

  @Test
  void mintString_shouldProduceCanonicalId_evenWhenDefaultLocaleIsTurkish() {
    // Arrange — Turkish locale lowercases 'I' to 'ı' by default; the minter
    // MUST use Locale.ROOT for the productName slug. Already true for the
    // enum overload; the parity guard ensures the String overload preserves it.
    Locale original = Locale.getDefault();
    Locale.setDefault(Locale.of("tr", "TR"));
    try {
      // Act
      String result =
          DataSourceIdMinter.mint("InsightIDR", "product_type", "microsoft-defender-endpoint");

      // Assert
      assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void mintString_shouldThrowNPE_whenProductNameNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint(null, "product_type", "x"))
        .withMessage("productName");
  }

  @Test
  void mintString_shouldThrowNPE_whenSourceTypeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", (String) null, "x"))
        .withMessage("sourceType");
  }

  @Test
  void mintString_shouldThrowNPE_whenSourceValueNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", null))
        .withMessage("sourceValue");
  }

  @Test
  void mintString_shouldThrowIAE_whenProductNameBlank() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("   ", "product_type", "x"))
        .withMessageContaining("productName")
        .withMessageContaining("blank");
  }

  @Test
  void mintString_shouldThrowIAE_whenSourceTypeBlank() {
    // Arrange — whitespace-only sourceType is treated like an empty enum miss:
    // not a degradation path, a programming error.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", "   ", "x"))
        .withMessageContaining("sourceType")
        .withMessageContaining("blank");
  }

  @Test
  void mintString_shouldThrowIAE_whenSourceTypeContainsDelimiter() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", "a|b", "x"))
        .withMessageContaining("sourceType")
        .withMessageContaining("|");
  }

  @Test
  void mintString_shouldThrowIAE_whenSourceValueEmpty() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", ""))
        .withMessageContaining("sourceValue")
        .withMessageContaining("empty");
  }

  @Test
  void mintString_shouldThrowIAE_whenSourceValueContainsDelimiter() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", "product_type", "a|b"))
        .withMessageContaining("sourceValue")
        .withMessageContaining("|");
  }
}
