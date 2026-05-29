package com.rapid7.integrationregistry.adapter.insightconnect;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InsightConnectPropertiesTest {

    private static final String URL = "https://icon.example";

    @Test
    void constructor_shouldAcceptValidValues_whenAllFieldsSet() {
        // Arrange / Act
        InsightConnectProperties props =
            new InsightConnectProperties(URL, URL, Duration.ofSeconds(3));
        // Assert
        assertThat(props.baseUrl()).isEqualTo(URL);
        assertThat(props.iconBase()).isEqualTo(URL);
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void constructor_shouldDefaultTimeout_whenTimeoutNull() {
        // Act
        InsightConnectProperties props = new InsightConnectProperties(URL, URL, null);
        // Assert
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void constructor_shouldStripTrailingSlash_fromBothUrls() {
        // Act
        InsightConnectProperties props =
            new InsightConnectProperties(URL + "/", URL + "/", null);
        // Assert — no double slash leaks into URL building or configuration_url templating
        assertThat(props.baseUrl()).isEqualTo(URL);
        assertThat(props.iconBase()).isEqualTo(URL);
    }

    @Test
    void constructor_shouldTrimSurroundingWhitespace_fromUrls() {
        // Act
        InsightConnectProperties props =
            new InsightConnectProperties("  " + URL + "  ", "  " + URL + "  ", null);
        // Assert
        assertThat(props.baseUrl()).isEqualTo(URL);
        assertThat(props.iconBase()).isEqualTo(URL);
    }

    @Test
    void constructor_shouldRejectNullBaseUrl() {
        assertThatNullPointerException()
            .isThrownBy(() -> new InsightConnectProperties(null, URL, null))
            .withMessageContaining("baseUrl");
    }

    @Test
    void constructor_shouldRejectNullIconBase() {
        assertThatNullPointerException()
            .isThrownBy(() -> new InsightConnectProperties(URL, null, null))
            .withMessageContaining("iconBase");
    }

    @Test
    void constructor_shouldRejectBlankBaseUrl() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InsightConnectProperties("   ", URL, null))
            .withMessageContaining("baseUrl");
    }

    @Test
    void constructor_shouldRejectBlankIconBase() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InsightConnectProperties(URL, "   ", null))
            .withMessageContaining("iconBase");
    }
}
