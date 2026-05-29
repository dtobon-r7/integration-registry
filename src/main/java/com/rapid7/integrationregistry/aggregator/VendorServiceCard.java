package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Primary grid row for {@code GET /vendor-services} per RFC-001 §Vendor Service entity. Embeds
 * {@code vendorId} and {@code vendorName} so the UI can render the vendor filter chip without a
 * separate lookup. {@code lastUpdated} is nullable per the RFC: null when no instance has yet
 * recorded a successful timestamp.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields are dictated by the RFC §Vendor Service entity, not by ergonomics.
public record VendorServiceCard(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    VendorCategory vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCount> integrationTypeCounts,
    List<String> productsConnected,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
  static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
  static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

  public VendorServiceCard {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
    Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    if (integrationsConnected < 0) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
    }
    integrationTypeCounts = List.copyOf(integrationTypeCounts);
    productsConnected = List.copyOf(productsConnected);
  }
}
