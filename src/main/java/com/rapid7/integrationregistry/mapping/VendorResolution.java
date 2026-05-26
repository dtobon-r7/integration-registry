package com.rapid7.integrationregistry.mapping;

import java.util.Objects;

public record VendorResolution(
    String vendorServiceId,
    String vendorServiceName,
    VendorCategory vendorCategory,
    String vendorId,
    String vendorName
) {

    static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
    static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
    static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";

    private static final VendorResolution UNKNOWN = new VendorResolution(
        "unknown", "Unknown", VendorCategory.OTHER, "unknown", "Unknown"
    );

    public VendorResolution {
        Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
        Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
        Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    }

    public static VendorResolution unknown() {
        return UNKNOWN;
    }
}
