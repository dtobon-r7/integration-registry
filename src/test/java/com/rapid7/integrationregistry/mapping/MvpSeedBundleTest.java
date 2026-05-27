package com.rapid7.integrationregistry.mapping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

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
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
        assertThat(resolution.vendorId()).isEqualTo("microsoft");
        assertThat(resolution.vendorName()).isEqualTo("Microsoft");
    }

    @Test
    void mvpSeed_shouldResolveDefenderViaICON_toMicrosoftDefender() {
        // Act
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "microsoft-defender");

        // Assert — proves the cross-product merge: ICON-side identifier resolves to the
        // SAME vendor service / vendor as the IDR-side identifier above.
        assertThat(resolution.vendorServiceId()).isEqualTo("microsoft-defender");
        assertThat(resolution.vendorServiceName()).isEqualTo("Microsoft Defender");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.EDR);
        assertThat(resolution.vendorId()).isEqualTo("microsoft");
        assertThat(resolution.vendorName()).isEqualTo("Microsoft");
    }

    @Test
    void mvpSeed_shouldResolveJiraViaICON_toJira() {
        // Act
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");

        // Assert
        assertThat(resolution.vendorServiceId()).isEqualTo("jira");
        assertThat(resolution.vendorServiceName()).isEqualTo("Jira");
        assertThat(resolution.vendorCategory()).isEqualTo(VendorCategory.ITSM);
        assertThat(resolution.vendorId()).isEqualTo("atlassian");
        assertThat(resolution.vendorName()).isEqualTo("Atlassian");
    }

    @Test
    void mvpSeed_shouldReturnUnknown_forUnmappedTriplet() {
        // Act — negative control: a triplet that is NOT in the seed.
        VendorResolution resolution = snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PLUGIN_NAME, "not-in-the-bundle");

        // Assert
        assertThat(resolution).isSameAs(VendorResolution.unknown());
    }
}
