package com.rapid7.integrationregistry.controller;

import com.rapid7.integrationregistry.controller.dto.ErrorCode;
import com.rapid7.integrationregistry.controller.dto.ErrorEnvelopeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Scoped to {@link VendorController} via {@code assignableTypes} so it governs only the read
 * edge — it does not silently capture {@code /actuator/health} or any controller added later, which
 * may need different error semantics. The {@code @WebMvcTest(VendorController.class)} slice cannot
 * observe over-broad advice scope, so the scope is pinned here rather than left to the global
 * default.
 *
 * <p>The catch-all binds {@link RuntimeException}, deliberately NOT {@link Exception}: Spring's
 * {@code MissingRequestHeaderException} is a checked {@code ServletException}, so an absent
 * identity header stays Spring's natural 400 instead of being swallowed into a 500. {@code
 * VendorService}'s {@code reasonOf()} {@code IllegalStateException} (a {@code RuntimeException})
 * maps to 500 here.
 */
@RestControllerAdvice(assignableTypes = VendorController.class)
class ReadApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ReadApiExceptionHandler.class);
  private static final String INTERNAL_MESSAGE = "Internal error";

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ErrorEnvelopeDto> handleNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(envelope(ErrorCode.NOT_FOUND, ex.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ErrorEnvelopeDto> handleInternal(RuntimeException ex) {
    // A 500 is by definition unexpected: log the cause server-side for diagnosis, but return a
    // fixed generic message so no exception detail leaks to the client.
    log.error("Unhandled read-path exception; returning 500 INTERNAL", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(envelope(ErrorCode.INTERNAL, INTERNAL_MESSAGE));
  }

  private static ErrorEnvelopeDto envelope(ErrorCode code, String message) {
    return new ErrorEnvelopeDto(new ErrorEnvelopeDto.ErrorBody(code, message));
  }
}
