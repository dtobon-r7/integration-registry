package com.rapid7.integrationregistry.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.rapid7.integrationregistry.mapping.SourceType;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DataSourceIdMinterTest {

  // RFC-001 §Data Model → data_source_id construction — three canonical examples.
  @Test
  void mint_shouldProduceCanonicalId_forInsightIdrProductTypeVector() {
    // Act
    String result =
        DataSourceIdMinter.mint(
            "InsightIDR", SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

    // Assert
    assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
  }

  @Test
  void mint_shouldProduceCanonicalId_forInsightConnectPluginNameVector() {
    // Act
    String result =
        DataSourceIdMinter.mint("InsightConnect", SourceType.PLUGIN_NAME, "microsoft-defender");

    // Assert
    assertThat(result).isEqualTo("insightconnect|plugin_name|microsoft-defender");
  }

  @Test
  void mint_shouldProduceCanonicalId_forSurfaceCommandIntegrationIdVector() {
    // Act
    String result =
        DataSourceIdMinter.mint(
            "Surface Command",
            SourceType.INTEGRATION_ID,
            "com.rapid7.microsoft-defender-for-endpoint");

    // Assert
    assertThat(result)
        .isEqualTo("surface-command|integration_id|com.rapid7.microsoft-defender-for-endpoint");
  }

  @Test
  void mint_shouldProduceCanonicalId_evenWhenDefaultLocaleIsTurkish() {
    // Arrange — Turkish locale lowercases 'I' to lowercase-dotless-ı by default;
    // RFC-001 demands a deterministic ASCII slug, so the minter MUST use Locale.ROOT.
    Locale original = Locale.getDefault();
    Locale.setDefault(Locale.of("tr", "TR"));
    try {
      // Act
      String result =
          DataSourceIdMinter.mint(
              "InsightIDR", SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

      // Assert
      assertThat(result).isEqualTo("insightidr|product_type|microsoft-defender-endpoint");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void mint_shouldThrowNPE_whenProductNameNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint(null, SourceType.PRODUCT_TYPE, "x"))
        .withMessage("productName");
  }

  @Test
  void mint_shouldThrowNPE_whenSourceTypeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", null, "x"))
        .withMessage("sourceType");
  }

  @Test
  void mint_shouldThrowNPE_whenSourceValueNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, null))
        .withMessage("sourceValue");
  }

  @Test
  void mint_shouldThrowIAE_whenProductNameEmpty() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("", SourceType.PRODUCT_TYPE, "x"))
        .withMessageContaining("productName")
        .withMessageContaining("blank");
  }

  @Test
  void mint_shouldThrowIAE_whenProductNameWhitespaceOnly() {
    // Arrange — a productName of only spaces would pass an .isEmpty() check
    // but produce a malformed slug like "---" after .replace(' ', '-').
    // .isBlank() is the correct gate.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("   ", SourceType.PRODUCT_TYPE, "x"))
        .withMessageContaining("productName")
        .withMessageContaining("blank");
  }

  @Test
  void mint_shouldThrowIAE_whenSourceValueEmpty() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, ""))
        .withMessageContaining("sourceValue")
        .withMessageContaining("empty");
  }

  @Test
  void mint_shouldThrowIAE_whenSourceValueContainsDelimiter() {
    // Arrange — '|' would make the composite ambiguously parseable
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DataSourceIdMinter.mint("X", SourceType.PRODUCT_TYPE, "a|b"))
        .withMessageContaining("sourceValue")
        .withMessageContaining("|");
  }
}
