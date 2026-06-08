package com.rapid7.integrationregistry.controller;

import com.rapid7.integrationregistry.controller.dto.ErrorCode;
import com.rapid7.integrationregistry.controller.dto.ErrorEnvelopeDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place read-path error envelopes are constructed (work plan: "Centralized
 * error-envelope emission for the read path"). Emits only {@code NOT_FOUND} (404) and {@code
 * INTERNAL} (500); never {@code UNAUTHENTICATED}/401 (T10's inbound filter chain owns that) and
 * never {@code FORBIDDEN}/{@code CONFLICT}/{@code VALIDATION} (the Registry has no write/admin
 * path).
 *
 * <p>The catch-all binds {@link RuntimeException}, deliberately NOT {@link Exception}: Spring's
 * {@code MissingRequestHeaderException} is a checked {@code ServletException}, so an absent
 * identity header stays Spring's natural 400 instead of being swallowed into a 500. {@code
 * VendorService}'s {@code reasonOf()} {@code IllegalStateException} (a {@code RuntimeException})
 * maps to 500 here.
 */
@RestControllerAdvice
class ReadApiExceptionHandler {

  private static final String INTERNAL_MESSAGE = "Internal error";

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ErrorEnvelopeDto> handleNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(envelope(ErrorCode.NOT_FOUND, ex.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ErrorEnvelopeDto> handleInternal(RuntimeException ex) {
    // The exception detail is intentionally not leaked to the client; the message is fixed.
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(envelope(ErrorCode.INTERNAL, INTERNAL_MESSAGE));
  }

  private static ErrorEnvelopeDto envelope(ErrorCode code, String message) {
    return new ErrorEnvelopeDto(new ErrorEnvelopeDto.ErrorBody(code, message));
  }
}
