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
        // Each block throws one type and catches that exact type, then asserts the
        // caught instance is NOT one of the other two. If a future refactor made one
        // type inherit from another (or introduced a shared parent above Exception),
        // the negative isNotInstanceOf assertions would fail.

        // Act + Assert — Timeout
        try {
            throw new AdapterTimeoutException("timeout");
        } catch (AdapterTimeoutException caught) {
            assertThat(caught)
                    .isNotInstanceOf(AdapterAuthException.class)
                    .isNotInstanceOf(AdapterUpstreamException.class);
        }

        // Act + Assert — Auth
        try {
            throw new AdapterAuthException("auth");
        } catch (AdapterAuthException caught) {
            assertThat(caught)
                    .isNotInstanceOf(AdapterTimeoutException.class)
                    .isNotInstanceOf(AdapterUpstreamException.class);
        }

        // Act + Assert — Upstream
        try {
            throw new AdapterUpstreamException("upstream");
        } catch (AdapterUpstreamException caught) {
            assertThat(caught)
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
