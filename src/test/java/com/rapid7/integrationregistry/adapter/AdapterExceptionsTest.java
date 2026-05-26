package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterExceptionsTest {

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterTimeoutExceptionThrown() {
        // Arrange
        String message = "ICON read timeout after 5s";

        // Act
        AdapterTimeoutException thrown = new AdapterTimeoutException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterAuthExceptionThrown() {
        // Arrange
        String message = "401 from ICON";

        // Act
        AdapterAuthException thrown = new AdapterAuthException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterUpstreamExceptionThrown() {
        // Arrange
        String message = "ICON 503";

        // Act
        AdapterUpstreamException thrown = new AdapterUpstreamException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterTimeoutExceptionThrown() {
        // Arrange
        String message = "ICON read timeout";
        Throwable cause = new RuntimeException("socket read deadline");

        // Act
        AdapterTimeoutException thrown = new AdapterTimeoutException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterAuthExceptionThrown() {
        // Arrange
        String message = "401 from ICON";
        Throwable cause = new RuntimeException("WebClientResponseException 401");

        // Act
        AdapterAuthException thrown = new AdapterAuthException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterUpstreamExceptionThrown() {
        // Arrange
        String message = "ICON 503";
        Throwable cause = new RuntimeException("WebClientResponseException 503");

        // Act
        AdapterUpstreamException thrown = new AdapterUpstreamException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void independentlyCatchable_shouldDistinguishEachType_whenThrownInSeparateBlocks() {
        // Arrange
        // Each try block throws exactly one of the three types and catches Exception,
        // then asserts the caught instance IS the expected type and is NOT either of
        // the others. If a future refactor introduced a common parent above Exception
        // (or made one type inherit from another), the negative isNotInstanceOf
        // assertions would fail.
        //
        // Note: a multi-catch like `catch (AdapterAuthException | AdapterUpstreamException)`
        // would not compile here, because checked exceptions in a multi-catch must be
        // reachable from the try block — only one type is thrown per block.

        // Act + Assert — Timeout
        try {
            throw new AdapterTimeoutException("timeout");
        } catch (Exception caught) {
            assertThat(caught)
                    .isInstanceOf(AdapterTimeoutException.class)
                    .isNotInstanceOf(AdapterAuthException.class)
                    .isNotInstanceOf(AdapterUpstreamException.class);
        }

        // Act + Assert — Auth
        try {
            throw new AdapterAuthException("auth");
        } catch (Exception caught) {
            assertThat(caught)
                    .isInstanceOf(AdapterAuthException.class)
                    .isNotInstanceOf(AdapterTimeoutException.class)
                    .isNotInstanceOf(AdapterUpstreamException.class);
        }

        // Act + Assert — Upstream
        try {
            throw new AdapterUpstreamException("upstream");
        } catch (Exception caught) {
            assertThat(caught)
                    .isInstanceOf(AdapterUpstreamException.class)
                    .isNotInstanceOf(AdapterTimeoutException.class)
                    .isNotInstanceOf(AdapterAuthException.class);
        }
    }
}
