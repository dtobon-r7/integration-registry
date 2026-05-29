package com.rapid7.integrationregistry.aggregator;

import java.util.Objects;

/**
 * Lightweight feed for the {@code GET /vendors} filter dropdown — vendor identity
 * plus the count of vendor services with at least one integration in the
 * requesting org. Per RFC-001 §Read API Contract → Projections this projection is
 * intentionally narrow: no {@code aggregate_health}, no {@code vendor_category}.
 */
public record VendorCard(
    String vendorId,
    String vendorName,
    int vendorServicesCount
) {

    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";

    public VendorCard {
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        if (vendorServicesCount < 0) {
            throw new IllegalArgumentException(
                FIELD_VENDOR_SERVICES_COUNT + " must be >= 0: " + vendorServicesCount);
        }
    }
}
