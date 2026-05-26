package com.rapid7.integrationregistry.adapter;

import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterExceptionsTest {

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterTimeoutExceptionThrown() {
        assertMessageRoundTrip(AdapterTimeoutException::new, "ICON read timeout after 5s");
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterAuthExceptionThrown() {
        assertMessageRoundTrip(AdapterAuthException::new, "401 from ICON");
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterUpstreamExceptionThrown() {
        assertMessageRoundTrip(AdapterUpstreamException::new, "ICON 503");
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterTimeoutExceptionThrown() {
        assertMessageAndCauseRoundTrip(
            AdapterTimeoutException::new, "ICON read timeout", new RuntimeException("socket read deadline"));
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterAuthExceptionThrown() {
        assertMessageAndCauseRoundTrip(
            AdapterAuthException::new, "401 from ICON", new RuntimeException("WebClientResponseException 401"));
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterUpstreamExceptionThrown() {
        assertMessageAndCauseRoundTrip(
            AdapterUpstreamException::new, "ICON 503", new RuntimeException("WebClientResponseException 503"));
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

    private static void assertMessageRoundTrip(Function<String, ? extends Exception> ctor, String message) {
        Exception thrown = ctor.apply(message);
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    private static void assertMessageAndCauseRoundTrip(
        BiFunction<String, Throwable, ? extends Exception> ctor, String message, Throwable cause) {
        Exception thrown = ctor.apply(message, cause);
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }
}
