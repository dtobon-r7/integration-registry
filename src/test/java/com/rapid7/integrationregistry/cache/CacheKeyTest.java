package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CacheKeyTest {

  @Test
  void of_shouldBuildFreshKey_whenTierIsFresh() {
    // Act
    String key = CacheKey.of(CacheTier.FRESH, "org-123", "InsightConnect");

    // Assert
    assertThat(key).isEqualTo("ir:cache:fresh:org-123:InsightConnect");
  }

  @Test
  void of_shouldBuildStaleKey_whenTierIsStale() {
    // Act
    String key = CacheKey.of(CacheTier.STALE, "org-123", "InsightIDR");

    // Assert
    assertThat(key).isEqualTo("ir:cache:stale:org-123:InsightIDR");
  }

  @Test
  void of_shouldProduceDistinctKeysPerTier_whenOrgAndProductIdentical() {
    // Act
    String fresh = CacheKey.of(CacheTier.FRESH, "org-1", "InsightConnect");
    String stale = CacheKey.of(CacheTier.STALE, "org-1", "InsightConnect");

    // Assert — distinct keys are what make the two tiers independent in Valkey
    assertThat(fresh).isNotEqualTo(stale);
  }

  @Test
  void of_shouldThrow_whenTierNull() {
    // Act / Assert
    assertThatThrownBy(() -> CacheKey.of(null, "org-123", "InsightConnect"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void of_shouldThrow_whenOrgIdNull() {
    // Act / Assert
    assertThatThrownBy(() -> CacheKey.of(CacheTier.FRESH, null, "InsightConnect"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void of_shouldThrow_whenProductNameNull() {
    // Act / Assert
    assertThatThrownBy(() -> CacheKey.of(CacheTier.FRESH, "org-123", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void of_shouldThrow_whenOrgIdContainsColon() {
    // Act / Assert
    assertThatThrownBy(() -> CacheKey.of(CacheTier.FRESH, "org:evil", "InsightConnect"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orgId must not contain ':'");
  }

  @Test
  void of_shouldThrow_whenProductNameContainsColon() {
    // Act / Assert
    assertThatThrownBy(() -> CacheKey.of(CacheTier.FRESH, "org-123", "Product:Evil"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("productName must not contain ':'");
  }
}
