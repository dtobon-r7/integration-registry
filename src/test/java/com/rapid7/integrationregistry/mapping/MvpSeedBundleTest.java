package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MvpSeedBundleTest {

  private static final String SEED_CLASSPATH = "/vendor-mapping/bundle/mvp-seed.yaml";

  private static VendorMappingSnapshot snapshot;

  @BeforeAll
  static void parseSeed() throws Exception {
    BundleParser parser = new BundleParser();
    try (InputStream stream = MvpSeedBundleTest.class.getResourceAsStream(SEED_CLASSPATH)) {
      assertThat(stream)
          .as("MVP seed resource %s present on classpath", SEED_CLASSPATH)
          .isNotNull();
      snapshot = parser.parse(stream);
    }
  }

  @Test
  void mvpSeed_shouldExposeMappingVersion() {
    // Act
    String version = snapshot.mappingVersion();

    // Assert
    assertThat(version).isEqualTo("v1.0.0");
  }

  @Test
  void mvpSeed_shouldResolveDefenderViaIDR_toMicrosoftDefender() {
    // Act
    DataSourceResolution resolution =
        snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

    // Assert
    assertThat(resolution.identity().vendorServiceId()).isEqualTo("microsoft-defender");
    assertThat(resolution.identity().vendorServiceName()).isEqualTo("Microsoft Defender");
    assertThat(resolution.identity().vendorCategory()).isEqualTo(VendorCategory.EDR);
    assertThat(resolution.identity().vendorId()).isEqualTo("microsoft");
    assertThat(resolution.identity().vendorName()).isEqualTo("Microsoft");
    assertThat(resolution.displayName()).isEqualTo("Microsoft Defender for Endpoint");
  }

  @Test
  void mvpSeed_shouldResolveDefenderViaICON_toMicrosoftDefender() {
    // Act
    DataSourceResolution resolution =
        snapshot.lookup(
            ProductName.INSIGHT_CONNECT,
            SourceType.PLUGIN_NAME,
            "rapid7_insightconnect_microsoft_defender");

    // Assert — proves the cross-product merge: ICON-side identifier resolves to the
    // SAME vendor service / vendor as the IDR-side identifier above.
    assertThat(resolution.identity().vendorServiceId()).isEqualTo("microsoft-defender");
    assertThat(resolution.identity().vendorServiceName()).isEqualTo("Microsoft Defender");
    assertThat(resolution.identity().vendorCategory()).isEqualTo(VendorCategory.EDR);
    assertThat(resolution.identity().vendorId()).isEqualTo("microsoft");
    assertThat(resolution.identity().vendorName()).isEqualTo("Microsoft");
    assertThat(resolution.displayName()).isEqualTo("Microsoft Defender");
  }

  @Test
  void mvpSeed_shouldResolveJiraViaICON_toJira() {
    // Act
    DataSourceResolution resolution =
        snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "rapid7_insightconnect_jira");

    // Assert
    assertThat(resolution.identity().vendorServiceId()).isEqualTo("jira");
    assertThat(resolution.identity().vendorServiceName()).isEqualTo("Jira");
    assertThat(resolution.identity().vendorCategory()).isEqualTo(VendorCategory.ITSM);
    assertThat(resolution.identity().vendorId()).isEqualTo("atlassian");
    assertThat(resolution.identity().vendorName()).isEqualTo("Atlassian");
    assertThat(resolution.displayName()).isEqualTo("Jira");
  }

  @Test
  void mvpSeed_shouldReturnUnknown_forUnmappedTriplet() {
    // Act — negative control: a triplet that is NOT in the seed.
    DataSourceResolution resolution =
        snapshot.lookup(ProductName.INSIGHT_IDR, SourceType.PLUGIN_NAME, "not-in-the-bundle");

    // Assert
    assertThat(resolution).isSameAs(DataSourceResolution.unknown());
  }
}
