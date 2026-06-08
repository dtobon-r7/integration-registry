package com.rapid7.integrationregistry.controller;

/**
 * Raised by {@link VendorController} when a detail route's {@code VendorService} result is {@code
 * Optional.empty()} — the service's signal that fresh AND stale data both confirm the org has no
 * integrations under the requested id (RFC-001 §"404 vs partial unavailability"). Translated to a
 * 404 {@code NOT_FOUND} envelope by {@link ReadApiExceptionHandler}. Exists so the controller never
 * hand-builds an error envelope; the advice stays the single emission point.
 */
class ResourceNotFoundException extends RuntimeException {

  ResourceNotFoundException(String message) {
    super(message);
  }
}
