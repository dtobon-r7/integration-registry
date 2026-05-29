package com.rapid7.integrationregistry.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

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
        AdapterTimeoutException::new,
        "ICON read timeout",
        new RuntimeException("socket read deadline"));
  }

  @Test
  void messageCauseCtor_shouldRoundTripBoth_whenAdapterAuthExceptionThrown() {
    assertMessageAndCauseRoundTrip(
        AdapterAuthException::new,
        "401 from ICON",
        new RuntimeException("WebClientResponseException 401"));
  }

  @Test
  void messageCauseCtor_shouldRoundTripBoth_whenAdapterUpstreamExceptionThrown() {
    assertMessageAndCauseRoundTrip(
        AdapterUpstreamException::new,
        "ICON 503",
        new RuntimeException("WebClientResponseException 503"));
  }

  @Test
  void parentClass_shouldBeAdapterException_forAllConcreteSubclasses() {
    AdapterAuthException auth = new AdapterAuthException("auth");
    AdapterTimeoutException timeout = new AdapterTimeoutException("timeout");
    AdapterUpstreamException upstream = new AdapterUpstreamException("upstream");

    assertThat(auth.getClass().getSuperclass()).isEqualTo(AdapterException.class);
    assertThat(timeout.getClass().getSuperclass()).isEqualTo(AdapterException.class);
    assertThat(upstream.getClass().getSuperclass()).isEqualTo(AdapterException.class);
  }

  @Test
  void independentlyCatchable_shouldDistinguishEachType_whenThrownInSeparateBlocks() {
    // Sibling distinctness: a future refactor making one sibling inherit
    // from another would break the negative isNotInstanceOf assertions.
    try {
      throw new AdapterTimeoutException("timeout");
    } catch (AdapterTimeoutException caught) {
      assertThat(caught)
          .isNotInstanceOf(AdapterAuthException.class)
          .isNotInstanceOf(AdapterUpstreamException.class);
    }

    try {
      throw new AdapterAuthException("auth");
    } catch (AdapterAuthException caught) {
      assertThat(caught)
          .isNotInstanceOf(AdapterTimeoutException.class)
          .isNotInstanceOf(AdapterUpstreamException.class);
    }

    try {
      throw new AdapterUpstreamException("upstream");
    } catch (AdapterUpstreamException caught) {
      assertThat(caught)
          .isNotInstanceOf(AdapterTimeoutException.class)
          .isNotInstanceOf(AdapterAuthException.class);
    }
  }

  @Test
  void familyIndependence_shouldNotBeBundleException_whenAdapterExceptionThrown() {
    AdapterException caught = new AdapterAuthException("auth");
    assertThat(caught)
        .isNotInstanceOf(BundleParseException.class)
        .isNotInstanceOf(BundleLoadException.class);
  }

  @Test
  void isTransient_shouldReturnFalse_whenAdapterAuthExceptionThrown() {
    // Arrange
    AdapterAuthException ex = new AdapterAuthException("401");

    // Act / Assert
    assertThat(ex.isTransient()).isFalse();
  }

  @Test
  void isTransient_shouldReturnTrue_whenAdapterTimeoutExceptionThrown() {
    // Arrange
    AdapterTimeoutException ex = new AdapterTimeoutException("read timeout");

    // Act / Assert
    assertThat(ex.isTransient()).isTrue();
  }

  @Test
  void isTransient_shouldReturnTrue_whenAdapterUpstreamExceptionThrown() {
    // Arrange
    AdapterUpstreamException ex = new AdapterUpstreamException("503");

    // Act / Assert
    assertThat(ex.isTransient()).isTrue();
  }

  @Test
  void reasonCode_shouldReturnAuthFailure_whenAdapterAuthExceptionThrown() {
    // Arrange
    AdapterAuthException ex = new AdapterAuthException("401");

    // Act / Assert
    assertThat(ex.reasonCode()).isEqualTo("auth_failure");
  }

  @Test
  void reasonCode_shouldReturnTimeout_whenAdapterTimeoutExceptionThrown() {
    // Arrange
    AdapterTimeoutException ex = new AdapterTimeoutException("read timeout");

    // Act / Assert
    assertThat(ex.reasonCode()).isEqualTo("timeout");
  }

  @Test
  void reasonCode_shouldReturnUpstream5xx_whenAdapterUpstreamExceptionThrown() {
    // Arrange
    AdapterUpstreamException ex = new AdapterUpstreamException("503");

    // Act / Assert
    assertThat(ex.reasonCode()).isEqualTo("upstream_5xx");
  }

  private static void assertMessageRoundTrip(
      Function<String, ? extends Exception> ctor, String message) {
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
