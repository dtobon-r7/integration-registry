package com.rapid7.integrationregistry.aggregator.projection;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Vendor-scoped view for {@code GET /vendors/{vendor_id}} per RFC-001 §Read API Contract →
 * Projections. Carries the vendor header (with {@code aggregateHealth} and {@code lastUpdated}
 * rolled across this vendor's services) plus a nested list of {@link VendorServiceCard}. {@code
 * lastUpdated} is nullable per the RFC.
 */
public record VendorScopedView(
    String vendorId,
    String vendorName,
    int vendorServicesCount,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated,
    List<VendorServiceCard> vendorServices) {

  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
  static final String FIELD_VENDOR_SERVICES = "vendorServices";

  public VendorScopedView {
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
    if (vendorServicesCount < 0) {
      throw new IllegalArgumentException(
          FIELD_VENDOR_SERVICES_COUNT + " must be >= 0: " + vendorServicesCount);
    }
    if (vendorServicesCount != vendorServices.size()) {
      throw new IllegalArgumentException(
          FIELD_VENDOR_SERVICES_COUNT
              + " ("
              + vendorServicesCount
              + ") must equal "
              + FIELD_VENDOR_SERVICES
              + ".size() ("
              + vendorServices.size()
              + ")");
    }
    vendorServices = List.copyOf(vendorServices);
  }
}
