package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum ProductName {
    INSIGHT_IDR("InsightIDR"),
    INSIGHT_CONNECT("InsightConnect"),
    SURFACE_COMMAND("Surface Command"),
    INSIGHT_VM("InsightVM"),
    INSIGHT_CLOUD_SEC("InsightCloudSec"),
    INSIGHT_APP_SEC("InsightAppSec");

    private final String wireForm;

    ProductName(String wireForm) {
        this.wireForm = wireForm;
    }

    public String wireForm() {
        return wireForm;
    }

    public static Optional<ProductName> fromWireForm(String wireForm) {
        for (ProductName product : values()) {
            if (product.wireForm.equals(wireForm)) {
                return Optional.of(product);
            }
        }
        return Optional.empty();
    }
}
